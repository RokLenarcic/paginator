(ns org.clojars.roklenarcic.paginator
  "Paginator enables paginating multiple concurrent items with batching."
  (:require [clojure.core.async :refer [chan go-loop >! close! onto-chan! <!! alts!] :as async]
            [org.clojars.roklenarcic.paginator.impl :as impl])
  (:import (clojure.lang PersistentQueue)))

(defn merge-result
  "Updates paging state with result map. Anything in the result map overwrites current
   values in paging-state. The exception are keys:
   - :page (which increases by 1)
   - :items values are combined"
  [result paging-state]
  (impl/merge-ps result paging-state))

(defn merge-expand-result
  "Updates paging state with result map. Returns a collection with
  updated paging state + expansion.

   Anything in the result map overwrites current values in paging-state.
   The exception are keys:
   - :page (which increases by 1)
   - :items are not added, but instead they are processed through
    transducer xf and added to the returned collection"
  [result paging-state xf]
  (into [(impl/merge-ps (when result (assoc result :items [])) paging-state)] xf (:items result)))

(defn merge-expand-results
  "Updates paging states with the results + expansions, see merge-expand-result."
  [results paging-states xf]
  (let [m (reduce (fn [m r] (assoc m [(:entity-type r) (:id r)] r)) {} results)]
    (vec (mapcat #(merge-expand-result (m [(:entity-type %) (:id %)]) % xf) paging-states))))

(defn merge-results
  "Updates paging states with the results, see merge-result fn."
  [results paging-states]
  (let [m (reduce (fn [m r] (assoc m [(:entity-type r) (:id r)] r)) {} results)]
    (mapv #(merge-result (m [(:entity-type %) (:id %)]) %) paging-states)))

(defn entity-type
  "It returns entity-type of the first paging-state in the collection of paging states"
  [paging-states]
  (:entity-type (first paging-states)))

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

(defn engine
  "Defines a minimal map describing the paging engine. Use with-* to add more options.

  async-fn is a fn that takes a no-arg fn and runs it asynchronously, defaults to future-call

  Default buffer size for result channel is 100.
  Default concurrency is 1, see with-concurrency docs for meaning of this setting."
  ([] (engine 1))
  ([concurrency] (engine concurrency future-call))
  ([concurrency async-fn]
   (-> {:async-fn async-fn}
       (with-batcher false)
       (with-result-buf 100)
       (with-concurrency concurrency))))

(defn paginate*!
  "Returns a channel for inbound paging state maps and a channel where the
  paging state maps are returns with additional keys of :items or :exception.

  They are returned in the map as :in and :out keys respectively.

  get-pages-fn takes paging-states coll and returns new updated paging-states coll,
  use merge-result, merge-results functions to assist in updating paging states correctly with new
  items."
  [engine get-pages-fn]
  (let [e (impl/init-engine engine get-pages-fn)]
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
      (throw (ex-info "Exceptions occurred during paged calls" ex-data (-> ex-data :causes first :exception))))))

(defn paginate!
  "Loads pages with given entity-id pairs. Returns a vector of paging states.
  Any exceptions are rethrown. entity-ids should be a coll of pairs of entity-type and id."
  [engine get-pages-fn entity-ids]
  (let [{result-ch :out ch-in :in} (paginate*! engine get-pages-fn)
        _ (onto-chan! ch-in (mapv #(paging-state (first %) (second %)) entity-ids) true)

        result-coll (<!! (async/into [] result-ch))]
    (throw-states-exceptions result-coll)
    result-coll))

(defn paginate-coll!
  "Loads pages with given IDs. Returns a vector of vectors where each subvector
  represents items for the id at that index in input parameters. Any exceptions are rethrown."
  [engine get-pages-fn entity-type ids]
  (let [lookup (reduce #(assoc %1 [(:entity-type %2) (:id %2)] %2)
                       {}
                       (paginate! engine get-pages-fn (map #(vector entity-type %) ids)))]
    (mapv #(:items (lookup [entity-type %])) ids)))

(defn paginate-one!
  "Starts pagination on a single entity and returns items. It expects that there is only 1 result.

  get-item-fn should take 1 parameter, the sole paging state, with entity-type ::singleton, id nil"
  [engine get-page-fn]
  (-> (paginate! engine (fn [[s]] (get-page-fn s)) [[::singleton nil]]) first :items))
