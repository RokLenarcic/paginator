(ns org.clojars.roklenarcic.paginator.impl
  (:require [clojure.core.async :refer [go <! >! >!! chan promise-chan]]
            [org.clojars.roklenarcic.paginator.protocols :as proto]))

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

(defn update-existing-ps
  "Updates an existing pagestate map with results."
  [{:keys [parser]} result page-states]
  (let [cursors (proto/-cursors parser result)
        items (proto/-items parser result)]
    (mapv
      (fn [{:keys [entity-type id] :as page-state}]
        (-> page-state
            (update :pages inc)
            (update :items into (remove nil?) (items [entity-type id]))
            (assoc :page-cursor (cursors [entity-type id]))))
      page-states)))

(defn run!! [{:keys [params get-items-fn items-in-runner items-in-process parser] :as engine} paging-states]
  (try
    (let [result (get-items-fn params paging-states)
          new-states (proto/-new-entities parser result)
          returned-states (vec (concat (update-existing-ps engine result paging-states) new-states))]
      (swap! items-in-process + (count new-states))
      returned-states)
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

(defn run-from-queue!
  "Enqueues a batch with the runner if there is one in the queue and the runner has capacity. Returns new engine state."
  [{:keys [async-fn ch-result] :as engine}]
  (when-let [batch (and (has-capacity? engine) (peek (get-in engine [:batcher :q])))]
    (get-in engine [:batcher :q])
    (swap! (:items-in-runner engine) inc)
    (async-fn (fn [] (>!! ch-result (run!! engine batch))))
    (update-in engine [:batcher :q] pop)))

(defn finished? [engine]
  "Is the paging process finished?

  If input is closed+no tasks in the process."
  (and (nil? (:ch-in engine)) (zero? @(:items-in-process engine))))

(defn init-engine [engine params]
  (let [items-in-runner (atom 0)
        items-in-process (atom 0)]
    (assoc engine :items-in-runner items-in-runner
                  :items-in-process items-in-process
                  :params params
                  :ch-in (chan 15 (swap-xf! items-in-process inc))
                  :ch-out (chan (:buf-size engine) (swap-xf! items-in-process dec))
                  ;; always do increase first so both atoms cannot be 0 between operations
                  :ch-result (chan 100 (mapcat identity)))))

