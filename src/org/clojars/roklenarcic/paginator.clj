(ns org.clojars.roklenarcic.paginator
  "Paginator enables paginating multiple concurrent items with batching."
  (:require [clojure.core.async :refer [chan go-loop >! close! onto-chan! <!! alts!] :as async]
            [org.clojars.roklenarcic.paginator.impl :as impl]
            [org.clojars.roklenarcic.paginator.protocols :as proto])
  (:import (clojure.lang PersistentQueue)))

(defmulti get-items
          "Multimethod that should perform a request for a specific :entity-type and return a response."
          (fn [params paging-states] (:entity-type (first paging-states))))

(defn result-parser1
  "Returns a proto/ResultParser that expects that response has 1 item and 1 cursor

  - items-fn takes a batch-result returns a collection of items for the paging-state
  - cursor-fn takes a batch-result returns a cursor
  - new-entities-fn takes a batch-result and returns coll of paging states for new entities, defaults
  to a function that always returns []"
  ([items-fn cursor-fn] (result-parser1 items-fn cursor-fn (constantly [])))
  ([items-fn cursor-fn new-entities-fn]
   (reify proto/BatchParser
     (-cursors [this batch-result]
       (constantly (cursor-fn batch-result)))
     (-items [this batch-result]
       (constantly (items-fn batch-result)))
     (-new-entities [this batch-result]
       (new-entities-fn batch-result)))))

(defn result-parser
  "Returns a proto/ResultParser, the functions given have the same argument list as
   the protocol."
  [items-fn cursor-fn new-entities-fn]
  (reify proto/BatchParser
    (-cursors [this batch-result]
      (cursor-fn batch-result))
    (-items [this batch-result]
      (items-fn batch-result))
    (-new-entities [this batch-result]
      (new-entities-fn batch-result))))

(defn with-batcher
  "Add batching configuration to engine map.

  sorted? enables using sorted map instead of normal for keeping batches. This comes into play when
  you have multiple unfinished batches and one needs to be picked for processing, with sorted map
  the batches with lowest batch key go first.

  max-items is the batch size. If pipeline stalls because no batches are available with max size, smaller
  batches are issued.

  batch-fn is the function to generate batch key from paging state map. Defaults to :entity-type"
  ([engine sorted?] (with-batcher engine sorted? 1))
  ([engine sorted? ^long max-items]
   (with-batcher engine sorted? max-items :entity-type))
  ([engine sorted? ^long max-items batch-fn]
   (assoc engine :batcher
                 {:m (if sorted? (sorted-map) {})
                  :q PersistentQueue/EMPTY
                  :max-items max-items
                  :batch-fn batch-fn})))

(defn with-result-buf
  "The buffer size for the result channel."
  [engine buf-size]
  (assoc engine :buf-size buf-size))

(defn with-concurrency
  [engine concurrency]
  (assoc engine :max-concurrency concurrency))

(defn with-items-fn
  [engine get-items-fn]
  (assoc engine :get-items-fn get-items-fn))

(defn engine
  "Defines a minimal map describing the paging engine. Use with-* to add more options.

  async-fn is a fn that takes a no-arg fn and runs it asynchronously, defaults to future-call

  get-items-fn is a fn of (params, paging-states) that should return some sort of a response,
  that will be parsed by result-parser, defaults to get-items multimethod

  result-parser is a protocols/ResultParser instance

  Default buffer size for result channel is 100.
  Default concurrency is 1, see with-concurrency docs for meaning of this setting."
  ([result-parser] (engine result-parser get-items))
  ([result-parser get-items-fn] (engine result-parser get-items-fn future-call))
  ([result-parser get-items-fn async-fn]
   (-> {:async-fn async-fn
        :parser result-parser}
       (with-items-fn get-items-fn)
       (with-batcher false)
       (with-result-buf 100)
       (with-concurrency 1))))

(defn paginate*!
  "Returns a channel for inbound paging state maps and a channel where the
  paging state maps are returns with additional keys of :items or :exception.

  They are returned in the map as :in and :out keys respectively."
  [engine params]
  (let [e (impl/init-engine engine params)]
    (go-loop [{:keys [ch-out ch-in ch-result] :as engine} e]
      ;; run from queue is the only queue -> actually executing path
      (if-let [new-e (impl/run-from-queue! engine)]
        (recur new-e)
        (let [[v port] (alts! (remove nil? [ch-in ch-result (async/timeout 100)]))]
          (condp = port
            ch-in (recur (if v (impl/add-to-batch engine v) (assoc engine :ch-in nil)))
            ch-result (if (:page-cursor v true)
                        (recur (impl/add-to-batch engine v))
                        (do (>! ch-out v) (recur engine)))
            (if (impl/finished? engine)
              (close! ch-out)
              (recur (impl/force-enqueue engine)))))))
    {:in (:ch-in e)
     :out (:ch-out e)}))

(defn paging-state
  "Returns a base data-structure that describes initial state in paging of results based on some entity.

  Initially it doesn't have a :page-cursor key. If the key is present and nil, the entity is considered to
  be finished caching."
  [entity-type entity-id]
  {:id entity-id
   :entity-type entity-type
   :pages 0
   :items []})

(defn throw-states-exceptions
  "Throws any exceptions in the states given."
  [states]
  (let [ex-data
        (reduce
          (fn [acc states]
            (update acc :causes conj (select-keys states [:exception :id :entity-type])))
          {:causes []}
          (filter :exception states))]
    (when-not (empty? (:causes ex-data))
      (throw (ex-info "Exceptions occurred during paged calls" ex-data)))))

(defn paginate!
  "Loads pages with given entity-id pairs. Returns a vector of paging states.
  Any exceptions are rethrown. entity-ids should be a coll of pairs of entity-type and id."
  [engine params entity-ids]
  (let [{result-ch :out ch-in :in} (paginate*! engine params)
        _ (onto-chan! ch-in (mapv #(paging-state (first %) (second %)) entity-ids) true)

        result-coll (<!! (async/into [] result-ch))]
    (throw-states-exceptions result-coll)
    result-coll))

(defn paginate-coll!
  "Loads pages with given IDs. Returns a vector of vectors where each subvector
  represents items for the id at that index in input parameters. Any exceptions are rethrown."
  [engine params entity-type ids]
  (let [lookup (reduce #(assoc %1 [(:entity-type %2) (:id %2)] %2)
                       {}
                       (paginate! engine params (map #(vector entity-type %) ids)))]
    (mapv #(:items (lookup [entity-type %])) ids)))

(defn paginate-one!
  "Starts pagination on a single entity and returns items. It expects that there is only 1 result.

  Params is merged into paging states map."
  [engine params entity-type id]
  (-> (paginate! engine params [[entity-type id]]) first :items))
