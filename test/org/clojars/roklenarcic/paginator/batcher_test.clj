(ns org.clojars.roklenarcic.paginator.batcher-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator.batcher :as b]))

(deftest time-adjusted-ratio-test
  (are [time-elapsed ratio]
    (= ratio (b/time-adjusted-ratio 0.5 time-elapsed {:min-items-ratio 0 :time-boost-start 25 :time-boost-end 75}))
    0 0.5
    25 0.5
    50 1.0
    75 1.5
    100 1.5)
  (are [time-elapsed ratio]
    (= ratio (b/time-adjusted-ratio 0.2 time-elapsed {:min-items-ratio 0.4 :time-boost-start 25 :time-boost-end 75}))
    0 0.2
    25 0.2
    50 0.5
    75 0.8
    100 0.8))
