(ns org.clojars.roklenarcic.paginator
  "Paginator enables paginating multiple concurrent items with batching."
  (:require [org.clojars.roklenarcic.paginator.impl :as impl]
            [org.clojars.roklenarcic.paginator.batcher :as batcher])
  (:import (clojure.lang IPending)
           (java.util Comparator)
           (java.util.concurrent ConcurrentLinkedQueue PriorityBlockingQueue Semaphore)))

(defn extra-props
  "Returns map of extra properties on a PagingState, also works with maps"
  [paging-state] (impl/extra-props paging-state))

(defn unwrap
  "Unwraps PagingState, returns vector of items, where each item has extra props from PagingState added.
  If items are not maps an exception will be thrown."
  [paging-state] (impl/unwrap paging-state))

(defn unwrap-async
  "Unwraps async result from async-fn (or other such mechanism), catching exceptions, unwrapping them and
  combining stack traces."
  [fut]
  (impl/realized fut true))

(defn async-fn
  "Helper to wrap a function into one that executes async in 'future',
  with concurrency limited to concurrency. The returned fn has semaphone object in meta.

  If you pass another fn created by this function as concurrency, the returned function will share
  the concurrency limit with the other one."
  [f max-concurrency]
  (let [^Semaphore sem (or (::semaphore (meta max-concurrency)) (Semaphore. max-concurrency))]
    (with-meta
      (fn [& args] (.acquire sem)
        (future
          (try
            (apply f args)
            (finally (.release sem)))))
      {::semaphore sem})))

(defn batcher
  "Create batcher with parameters:
  - batch size
  - partial-batch-strategy (can be nil, :min-batches, or map of factors)"
  ([batch-size] (batcher batch-size nil))
  ([batch-size partial-batch-strategy] (batcher/grouped-batcher (constantly nil) batch-size partial-batch-strategy)))

(defn grouped-batcher
  "Create a batcher that groups by provided function. Returns batches of size n from
  each group as it fills up. Group fn receives a PagingState, and it should return the group.

  Partial batch strategy can be nil or :min-batches of a map of factors."
  ([group-fn batch-size] (grouped-batcher group-fn batch-size nil))
  ([group-fn batch-size partial-batch-strategy] (batcher/grouped-batcher group-fn batch-size partial-batch-strategy)))

(defn paginate!
  "Paginates a collection of items using run-fn."
  [run-fn {:keys [batcher pages? concurrency] :as options} input]
  (let [counters {:in-cnt (volatile! 0)}
        batcher (cond (nil? batcher) (batcher/non-batcher)
                      (integer? batcher) (batcher/grouped-batcher (constantly nil) batcher nil)
                      :else batcher)
        ;; if not lazy sequence, push items into batcher immediately and have nil in input to signal no more
        preload? (or (not (instance? IPending input))
                     (do (first input) (realized? input)))
        options (merge options
                       #::impl {:run-fn run-fn :batcher batcher :futs (ConcurrentLinkedQueue.)
                                :input (volatile! (when-not preload? input)) :counters counters
                                :results (PriorityBlockingQueue. 11 ^Comparator (fn [x y] (compare (:idx x) (:idx y))))
                                :concurrency concurrency :pages? pages?})]
    (when preload? (impl/finalize-result {:to-batch input} (assoc options ::impl/pages? false)))
    (impl/page-loop options 0)))

(defn paginate-one!
  "Paginates one item, using run-fn. Returns a sequence of maps, one for each item with merged
  properties from input into each.

  - input is a map, is converted to PagingState
  - run-fn can return a PagingState, a map (which will be converted to paging state) or Future or IPending
  - options: :paged?, if set to true the function returns a lazy sequence of PagingStates, one for each
                      page as it gets loaded"
  ([run-fn] (paginate-one! {} run-fn {}))
  ([input run-fn] (paginate-one! input run-fn {}))
  ([input run-fn {:keys [pages?] :as options}]
   (if pages?
     (let [res (impl/page-loop-solo input run-fn true)]
       (cons res
             (when (:cursor res)
               (lazy-seq (paginate-one! res run-fn options)))))
     (loop [page-state input]
       (let [res (impl/page-loop-solo page-state run-fn false)]
         (if (:cursor res) (recur res) res))))))
