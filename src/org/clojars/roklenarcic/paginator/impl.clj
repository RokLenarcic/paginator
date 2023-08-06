(ns org.clojars.roklenarcic.paginator.impl
  (:require [clojure.set :refer [rename-keys]]
            [org.clojars.roklenarcic.paginator.batcher :refer [queue-front queue-back poll-batch batcher-empty?]])
  (:import (clojure.lang IDeref IPending)
           (java.util ArrayList Queue)
           (java.util.concurrent ExecutionException Future PriorityBlockingQueue Semaphore)))

(defrecord PagingState [items cursor pages add-page idx])
(def empty-state (->PagingState [] nil 0 nil 0))
(def paging-state-props [:items :cursor :pages :add-page :idx])
(defn extra-props [p] (apply dissoc p paging-state-props))
(defn validate-extra-props [p]
  (run! #(when-let [[prop _] (find p %)]
           (throw (ex-info (str "Input contains map key already used by PagingState record: " prop)
                           {:in p})))
        paging-state-props))

(defn merge-to-ps [ps x]
  (let [m (if (map? x) x {:id x})]
    (validate-extra-props m)
    (merge ps m)))

(defn unwrap [p]
  (if-let [props (and (instance? PagingState p) (extra-props p))]
    (mapv #(if (map? %)
             (merge props %)
             (throw (ex-info (format "Result item %s is not a map. If you wish to return other types of items specify :wrapped? true in options." % )
                             {:paging-state p})))
          (:items p))
    p))

(defn clean-stack-trace
  "Return executionexception stacktrace with stack cleaned at the top"
  [^Throwable ee]
  (let [cause (.getCause ee)
        bottom-st (drop-while #(not= "clojure.core$deref" (.getClassName ^StackTraceElement %)) (.getStackTrace ee))]
    (.setStackTrace (or cause ee) (into-array StackTraceElement (concat (some-> cause (.getStackTrace)) bottom-st)))
    (or cause ee)))

(defn- state-add-page-fn
  "Creates the add page fn"
  [paging-state replace-items?]
  (fn state-add-page
    ([page] (state-add-page page nil nil))
    ([page cursor] (state-add-page page cursor nil))
    ([page cursor other-props]
     (-> (if other-props
           (merge-to-ps paging-state other-props)
           paging-state)
         (update :items (if replace-items? (fn [_ p] p) into) page)
         (update :pages inc)
         (assoc :cursor cursor)))))

(defn deferred? [x] (or (instance? Future x) (and (instance? IPending x)
                                                  (instance? IDeref x))))
(defn spare-concurrency?
  "Returns true if there seems to be concurrency to spare. Always
  true if user didn't specify concurrency limit and it wasn't measured."
  [run-fn options]
  (if-let [^Semaphore sem (:org.clojars.roklenarcic.paginator/semaphore (meta run-fn))]
    (pos-int? (.availablePermits sem))
    (let [used (.size ^Queue (::futs options))
          allowed (::concurrency options)]
      (or (nil? allowed) (< used @allowed)))))

(defn realized
  "Returns realized result or nil"
  [res block?]
  (try
    (condp instance? res
      IPending (when (or block? (realized? res)) @res)
      Future (when (or block? (.isDone ^Future res)) @res)
      res)
    (catch ExecutionException ee
      (throw (clean-stack-trace ee)))))

(defn vol++ [v] (doto @v (->> inc (vreset! v))))

;; processed items map is a map:
;; - :to-batch, :to-ret, :pending
;;  if the operation did something nothing that moves the process along it returns nil

(defn read-input
  "Tries to gather an item from input returns processed items map, if able."
  [input]
  (let [curr-input @input]
    (if (seq curr-input)
      (do (vreset! input (rest curr-input))
          {:to-batch [(first curr-input)]})
      (vreset! input nil))))

(defn process-ret
  "Process a return, returning a processed items map."
  [ret]
  (cond
    (deferred? ret) {:pending ret}
    (sequential? ret) (rename-keys (group-by #(and (instance? PagingState %) (nil? (:cursor %))) ret)
                                   {true :to-ret false :to-batch})
    :else (process-ret [ret])))

(defn poll-futures
  "Iterates the futures Queue, finds any finished futures, processes the return,
  returning processed items map or nil if no future has been resolved."
  [^Queue futs]
  (let [iter (.iterator futs)]
    (loop [ret nil]
      (if-let [it (and (.hasNext iter) (.next iter))]
        (if-let [real (realized it false)]
          (do (.remove iter) (recur (merge-with into ret (process-ret real))))
          (recur ret))
        ret))))

(defn instr-batch
  "Add :add-page function to all items."
  [items emit-pages?]
  (let [instr #(assoc % :add-page (state-add-page-fn % emit-pages?))]
    (if (instance? PagingState items) (instr items) (map instr items))))

(defn finalize-result
  "Act on operation result, sending items to their respective queues."
  [result {::keys [batcher ^Queue futs counters ^Queue results pages?]}]
  (let [idx-counter (:in-cnt counters)
        {:keys [to-ret to-batch pending]} result
        to-batch-groups (group-by #(instance? PagingState %) to-batch)]
    (run! #(.offer results %) to-ret)
    (when pages? (run! #(.offer results %) to-batch))
    (run! #(queue-back batcher (assoc (merge-to-ps empty-state %) :idx (vol++ idx-counter))) (to-batch-groups false))
    (run! #(queue-front batcher %) (reverse (to-batch-groups true)))
    (when pending (.offer futs pending))
    result))

(defn- no-batcher-inputs? [^Queue futs input]
  (and (.isEmpty futs) (nil? @input)))

(defn- processing-done? [{::keys [batcher futs input]}]
  (and (batcher-empty? batcher) (no-batcher-inputs? futs input)))

(defn page-loop
  "Dual queue synchronous page-loop. Input is a volatile containing items and is lazily read when queue is empty.

  Tries to finish one of the tasks that moves the engine along. If task returns a map, even an empty one,
  it is considered to have done something and the next tasks are not tried. The list of finished items is pushed
  into a priority queue based on :idx of PagingState. This enables that we can emit results in order of inputs if
  desired.

  PagingStates are tagged with increasing idx."
  [{::keys [run-fn batcher ^Queue futs input ^PriorityBlockingQueue results pages?] :as options} out-cnt]
  (loop []
    (let [first-item (.peek results)]
      ;; shuttle results into heap for sorting, emit them sorted
      ;; if pages returning is enabled the whole out-cnt is ignored
      (if (and first-item (or pages? (= out-cnt (:idx first-item))))
        (do (.poll results)
            (cons first-item (lazy-seq (page-loop options (inc out-cnt)))))
        ;; try to clear any finished futures, moving to result or batcher
        (let [items (or (poll-futures futs)
                        ;; if batcher has work available, try to run that
                        (when-some [batch (poll-batch batcher
                                                      (no-batcher-inputs? futs input)
                                                      (spare-concurrency? run-fn options))]
                          (process-ret (run-fn (instr-batch batch pages?))))
                        ;; else try waiting for input if there's more and enqueue that
                        (read-input input)
                        ;; don't spin too fast if nothing is going on
                        (Thread/sleep 1))]
          (finalize-result items options)
          (if (processing-done? options)
            (let [ll (ArrayList.)]
              ;; priority queue needs this operation to return items in correct order instead of just straight seq
              (.drainTo results ll)
              (seq ll))
            (recur)))))))

(defn page-loop-solo [input run-fn pages?]
  (let [page-state (if (instance? PagingState input) input (merge-to-ps empty-state input))]
    (as-> (run-fn (instr-batch page-state pages?)) res
          (if (deferred? res) (realized res true) res)
          (if (sequential? res) (first res) res))))
