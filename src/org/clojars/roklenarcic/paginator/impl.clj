(ns org.clojars.roklenarcic.paginator.impl
  (:require [clojure.core.async :refer [go <! >! >!! chan promise-chan]]))

(defn swap-xf!
  "A transducer that runs f on atom* for each item seen."
  [atom* f]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (swap! atom* f)
       (rf result input))
      ([result input & inputs]
       (swap! atom* f)
       (apply rf result input inputs)))))

;; RUNNER MANAGEMENT

(defn has-capacity?
  [engine]
  (< @(:items-in-runner engine) (:max-concurrency engine)))

(defn merge-ps
  "Updates an existing pagestate map with results. It will specially handle
  :pages, :items, :page-cursor, the rest of it is merged into paging-state as is."
  [result paging-state]
  (if result
    (-> paging-state
        (update :pages inc)
        (update :items into (remove nil?) (:items result))
        (assoc :page-cursor (:page-cursor result))
        (merge (dissoc result :pages :items :page-cursor)))
    ;; no result... but there should be some
    (assoc paging-state :page-cursor nil)))

(defn paging-state?
  "Returns true if the map looks like a paging state with cursor."
  [s]
  (and (map? s) (every? #(contains? s %) [:id :entity-type :items])))

(defn mark-invalid-state
  "If state is invalid, mark it with an exception"
  [s]
  (if (paging-state? s)
    s
    (assoc (if (map? s) s {})
      :exception (ex-info "Returned value doesn't seem to be a paging state, must have keys [:id :entity-type :items]."
                          {:invalid-state s})
      :page-cursor nil)))

(defn run!! [{:keys [get-pages-fn items-in-runner items-in-process]} paging-states]
  (try
    (let [returned-states (get-pages-fn paging-states)
          returned-states (if (map? returned-states) [returned-states] (vec returned-states))]
      (swap! items-in-process + (- (count returned-states) (count paging-states)))
      (mapv mark-invalid-state returned-states))
    (catch Exception e
      (mapv #(assoc % :exception e :page-cursor nil) paging-states))
    (finally
      (swap! items-in-runner dec))))

;; BATCH AND QUEUE MANAGEMENT

(defn add-to-batch
  "Adds item to batch, adds batch to queue if max-items was reached."
  [engine it]
  (let [{:keys [max-items batch-fn]} (:batcher engine)
        batch-key (batch-fn it)
        engine (update-in engine [:batcher :m batch-key] (fnil conj []) it)
        batch (get-in engine [:batcher :m batch-key])]
    (if (>= (count batch) max-items)
      (-> engine
          (update-in [:batcher :q] conj batch)
          (update-in [:batcher :m] dissoc batch-key))
      engine)))

(defn force-enqueue
  "Forces a batch to queue if queue is empty and the runner state allows for more tasks"
  [engine]
  (if-let [it (and (has-capacity? engine)
                   (nil? (peek (-> engine :batcher :q)))
                   (first (get-in engine [:batcher :m])))]
    (-> engine
        (update-in [:batcher :q] conj (val it))
        (update-in [:batcher :m] dissoc (key it)))
    engine))

(defn runner-fn [f ch-result] (future (>!! ch-result (f))))

(defn run-from-queue!
  "Enqueues a batch with the runner if there is one in the queue and the runner has capacity. Returns new engine state."
  [{:keys [runner-fn ch-result] :as engine}]
  (when-let [batch (and (has-capacity? engine) (peek (get-in engine [:batcher :q])))]
    (get-in engine [:batcher :q])
    (swap! (:items-in-runner engine) inc)
    (runner-fn (fn [] (run!! engine batch)) ch-result)
    (update-in engine [:batcher :q] pop)))

(defn finished? [engine]
  "Is the paging process finished?

  If input is closed+no tasks in the process."
  (and (nil? (:ch-in engine)) (zero? @(:items-in-process engine))))

(defn init-engine [engine get-pages-fn]
  (let [items-in-runner (atom 0)
        items-in-process (atom 0)]
    (assoc engine :items-in-runner items-in-runner
                  :items-in-process items-in-process
                  :get-pages-fn get-pages-fn
                  :ch-in (chan 15 (swap-xf! items-in-process inc))
                  :ch-out (chan (:buf-size engine) (swap-xf! items-in-process dec))
                  ;; always do increase first so both atoms cannot be 0 between operations
                  :ch-result (chan 100 (mapcat identity)))))

