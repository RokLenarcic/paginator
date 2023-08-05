(ns org.clojars.roklenarcic.paginator.batcher
  "Batchers follow Stack like behaviour when adding items instead of Queue because we want to run the same
  items in the row if at all possible, to finish some items instead of getting 1 pager for every item first."
  (:import (java.util LinkedList)))

(defprotocol Batcher
  "Mutable batching system. Batches returned can be collections or a single item."
  (queue-front [this item] "Adds item to batcher to the front.")
  (queue-back [this item] "Adds item to batcher to the back of the queue.")
  (poll-batch [this no-extra-input? spare-concurrency?] "Takes a batch, nil if none available")
  (batcher-empty? [this] "Returns true if batcher is empty"))

(deftype NonBatcher [^LinkedList l]
  Batcher
  (queue-front [this item] (locking l (.offerFirst l item)))
  (queue-back [this item] (locking l (.offerLast l item)))
  (poll-batch [this _no-extra-input? _spare-concurrency?] (locking l (.poll l)))
  (batcher-empty? [this] (locking l (.isEmpty l))))

(defn time-adjusted-ratio
  "Items ratio is ratio of size of items available vs batch size. Ratio of 1 is
  full batch. As time passes we adjust the ratio higher to eventually start sending
  partial batches.

  When time-elapsed is time-boost-end, the bonus to items-ratio is equal to (1 - min-items-ratio),
  so no matter how much time passes, if items-ratio is below minimum, it will never be 1 or more
  after time adjustment."
  [items-ratio time-elapsed {:keys [min-items-ratio time-boost-start time-boost-end]}]
  (let [time-ratio-boost (- 1 (or min-items-ratio 0))
        time-ratio (/ (max 0 (- time-elapsed time-boost-start)) (- time-boost-end time-boost-start))]
    (+ items-ratio (* (min time-ratio 1) time-ratio-boost))))

(defn emit-batch?
  "Adaptive strategy to emit a batch.
  - items-ratio is ratio of size of items available vs batch size
  - time-elapsed is the ratio of time since last batch vs desired batch time"
  [items-ratio time-elapsed no-extra-input? spare-concurrency? strategy]
  ;; always emit batch if no new items can enter the batcher
  (or no-extra-input?
      ;; always emit batch if there's enough items to fill the entire batch
      (>= items-ratio 1)
      ;; otherwise wait if concurrency will get blocked
      (and spare-concurrency?
           (pos? items-ratio)
           (>= (time-adjusted-ratio items-ratio time-elapsed strategy) 1))))

(defn non-batcher
  "A batcher that doesn't batch at all. Produces single items not wrapped in collection as batches."
  []
  (->NonBatcher (LinkedList.)))

(deftype GroupedBatcher [^:volatile-mutable batch-groups n ^:volatile-mutable last-batch-ts strategy grouping-fn]
  Batcher
  (queue-front [this item] (set! batch-groups (update batch-groups (grouping-fn item) conj item)))
  (queue-back [this item]
    (set! batch-groups (update batch-groups (grouping-fn item) #(seq (conj (vec %1) %2)) item)))
  (poll-batch [this no-extra-input? spare-concurrency?]
    (when (not-empty batch-groups)
      ;; find the biggest group
      (let [[k group] (apply max-key (comp count val) batch-groups)
            time-elapsed (- (System/currentTimeMillis) last-batch-ts)]
        (when (emit-batch? (/ (count group) n) time-elapsed no-extra-input? spare-concurrency? strategy)
          (let [[batch more] (split-at n group)]
            (set! batch-groups (if (seq more)
                                 (assoc batch-groups k more)
                                 (dissoc batch-groups k)))
            (set! last-batch-ts (System/currentTimeMillis))
            (vec (if (= n 1) (first batch) batch)))))))
  (batcher-empty? [this] (empty? batch-groups)))



(defn grouped-batcher
  [grouping-fn n strategy]
  (let [s (case strategy
            :min-batches {:min-items-ratio 1 :time-boost-start 30 :time-boost-end 100}
            (merge {:min-items-ratio 0.3 :time-boost-start 30 :time-boost-end 100} strategy))]
    (->GroupedBatcher {} (max n 1) 0 s grouping-fn)))
