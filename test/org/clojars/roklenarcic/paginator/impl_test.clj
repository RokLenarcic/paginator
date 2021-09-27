(ns org.clojars.roklenarcic.paginator.impl-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p]
            [org.clojars.roklenarcic.paginator.impl :as b])
  (:import (clojure.lang PersistentQueue)))

(defn engine-resolved-queue
  [e]
  (update-in e [:batcher :q] vec))

(deftest add-to-batch-test
  (let [engine (p/with-batcher (p/engine nil) false 2 #(mod % 10))
        engine2 (assoc-in engine [:batcher :m] {3 [3]})
        engine3 (assoc-in engine [:batcher :q] (conj PersistentQueue/EMPTY [3 13]))]
    (is (= engine2 (b/add-to-batch engine 3)))
    (is (= (engine-resolved-queue engine3) (engine-resolved-queue (b/add-to-batch engine2 13))))))

(deftest swap-xf-test
  (let [a (atom 0)]
    (is (= [1 2 3] (into [] (b/swap-xf! a dec) [1 2 3])))
    (is (= -3 @a))))
