(ns org.clojars.roklenarcic.paginator.impl-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p]
            [org.clojars.roklenarcic.paginator.impl :as b])
  (:import (java.util LinkedList)))

(deftest deferred-test
  (is (= false (b/deferred? 5)))
  (is (= true (b/deferred? (promise))))
  (is (= true (b/deferred? (future 1)))))

(deftest spare-concurrency-test
  (is (= true (b/spare-concurrency? identity {::b/concurrency (volatile! 5) ::b/futs (LinkedList. [1 2 3])})))
  (is (= false (b/spare-concurrency? identity {::b/concurrency (volatile! 5) ::b/futs (LinkedList. [1 2 3 4 5])})))
  (is (= true (b/spare-concurrency? identity {::b/futs (LinkedList. [1 2 3])})))
  (is (= true (b/spare-concurrency? (p/async-fn identity 1) {::b/futs (LinkedList. [1 2 3])})))
  (let [f (p/async-fn (fn [] (Thread/sleep 2000)) 1)]
    (f)
    (is (= false (b/spare-concurrency? f {::b/futs (LinkedList. [1 2 3])})))))

(deftest realized-test
  (let [p (promise) f (future (Thread/sleep 1000) 10)]
    (is (= nil (b/realized f)))
    (is (= 10 (b/realized @f)))
    (is (= nil (b/realized p)))
    (is (= 1 (do (deliver p 1) (b/realized p))))
    (is (= {} (b/realized {})))))

(deftest v++-test
  (let [v (volatile! 1)]
    (is (= 1 @v))
    (is (= 1 (b/vol++ v)))
    (is (= 2 @v))))

(deftest read-input-test
  (let [in (volatile! [1 2])]
    (is (= {:to-batch [1]} (b/read-input in)))
    (is (= [2] @in))
    (is (= {:to-batch [2]} (b/read-input in)))
    (is (= '() @in))
    (is (= nil (b/read-input in)))
    (is (= nil @in))))

(deftest process-ret-test
  (testing "Pending returns"
    (let [p (promise)]
      (is (= {:pending p} (b/process-ret p)))))
  (testing "Other returns"
    (is (= {:to-batch [1]} (b/process-ret 1)))
    (is (= {:to-batch [(assoc b/empty-state :cursor 1)]}
           (b/process-ret (assoc b/empty-state :cursor 1))))
    (is (= {:to-ret [b/empty-state]} (b/process-ret b/empty-state)))))

(deftest poll-futures-test
  (let [q (doto (LinkedList.)
            (.offer (future 1)) (.offer (future 2)) (.offer (future 3))
            (.offer (promise)) (.offer (future b/empty-state)))]
    (is (= {:to-batch [1 2 3] :to-ret [b/empty-state]} (b/poll-futures q)))))

(deftest unwrap-test
  (is (= 1 (b/unwrap 1)))
  (is (= [{:a 1 :b 5} {:a 2 :b 5} {:a 3 :b 5}] (b/unwrap (assoc (b/->PagingState [{:a 1} {:a 2} {:a 3}] 5 10 identity 14) :b 5))))
  (is (thrown? Exception (b/unwrap (assoc (b/->PagingState [:a 1 :a 2 :a 3] 5 10 identity 14) :b 5)))))
