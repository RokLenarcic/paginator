(ns org.clojars.roklenarcic.paginator-test
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p]
            [org.clojars.roklenarcic.paginator.impl :as b])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent Semaphore)))

(deftest concurrency-test
  (testing "concurrency is respected"
    (let [pages (atom 0)
          semaphore (Semaphore. 5)
          get-pages (fn [{:keys [add-page] :as x}]
                      (when-not (.tryAcquire semaphore)
                        (throw (ex-info "Too many concurrent threads" {})))
                      (Thread/sleep 10)
                      (.release semaphore)
                      (add-page [] (when (< (swap! pages inc) 200) true)))]
      (is (= (repeat 10 []) (map p/unwrap (p/paginate! (p/async-fn get-pages 5) {} (map #(assoc nil :id %) (range 10))))))
      (is (thrown? Exception (p/paginate! (p/async-fn get-pages 10) {} (map #(assoc nil :id %) (range 100))))))))

(defn get-from-vector
  "Mock pages 2 items per page"
  [v cursor]
  (let [p (or cursor 0)]
    {:body {:items (take 2 (drop p v))
            :offset (when (< (+ 2 p) (count v))
                      (+ 2 p))}}))

(def projects-of-accounts
  "Mock project accounts"
  (mapv
    #(mapv (fn [i] {:project (+ i (* 10 %)) :account %}) (range 10))
    (range 100)))

(defn projects-by-id
  "Mock getting project accounts paginated"
  [{:keys [cursor add-page account-id]}]
  (let [{:keys [items offset]} (:body (get-from-vector (projects-of-accounts account-id) cursor))]
    (add-page items offset)))

(defn ret-for-acc
  "Helper fn"
  [acc-id]
  (merge b/empty-state
         {:idx acc-id :account-id acc-id :items (projects-of-accounts acc-id) :pages 5}))

(defn ret-for-acc-pages
  "Helper fn"
  [acc-id]
  (let [ps (ret-for-acc acc-id)
        item-num (count (:items ps))
        pages (range 1 (inc (/ item-num 2)))]
    (map (fn [page]
           (let [cursor (* 2 page)]
             (assoc ps :items (subvec (:items ps) (- cursor 2) cursor)
                       :cursor (when-not (= item-num cursor) cursor)
                       :pages page)))
         pages)))

(defn get-account-projects-by-id [account-id cursor]
  (get-from-vector (projects-of-accounts account-id) cursor))

(defn account-projects [{:keys [account-id add-page cursor]}]
  (let [{:keys [items offset]} (:body (get-account-projects-by-id account-id cursor))]
    (add-page items offset)))

(defn multi-account-projects [page-states]
  (mapv #(account-projects %) page-states))

(deftest paginate-one-test
  (testing "simple paginate one"
    (is (= (ret-for-acc 0) (p/paginate-one! {:account-id 0} account-projects)))
    (is (= (ret-for-acc 0) (p/paginate-one! {:account-id 0} #(future (account-projects %))))))
  (testing "pages test"
    (is (= (ret-for-acc-pages 0)
           (p/paginate-one! {:account-id 0} projects-by-id {:pages? true}))))
  (testing "automatic input conversion"
    (is (= (-> (ret-for-acc 0) (set/rename-keys {:account-id :id}))
           (p/paginate-one! 0 (comp account-projects #(set/rename-keys % {:id :account-id})))))))

(deftest paginate-test
  (let [all-accounts (map #(assoc {} :account-id %) (range 10))
        all-ret (map ret-for-acc (range 10))
        all-pages (mapcat ret-for-acc-pages (range 10))
        sorted #(sort-by (juxt :account-id :project) %)]
    (testing "non-batched"
      (is (= all-ret (p/paginate! account-projects {} all-accounts)))
      (is (= all-ret (p/paginate! #(future (account-projects %)) {} all-accounts)))
      (is (= all-pages (sorted (p/paginate! account-projects {:pages? true} all-accounts)))))
    (testing "batched"
      (is (= all-ret (p/paginate! multi-account-projects {:batcher 3} all-accounts)))
      (is (= all-ret (p/paginate! #(future (multi-account-projects %)) {:batcher 3} all-accounts)))
      (is (= (sorted all-pages)
             (sorted (p/paginate! #(future (multi-account-projects %))
                                  {:pages? true :batcher 3}
                                  all-accounts)))))
    (testing "group batched"
      (is (= all-ret (p/paginate! multi-account-projects {:batcher (p/grouped-batcher (comp even? :idx) 3)} all-accounts)))
      (is (= all-ret (p/paginate! #(future (multi-account-projects %)) {:batcher (p/grouped-batcher (comp even? :idx) 3)} all-accounts)))
      (is (= (sorted all-pages)
             (sorted (p/paginate! #(future (multi-account-projects %))
                                  {:pages? true :batcher (p/grouped-batcher (comp even? :idx) 3)}
                                  all-accounts)))))))

(defn broken-function [x]
  (throw (ex-info "No bueno" {:test 1})))

(deftest exception-tests
  (is (thrown? ExceptionInfo (p/paginate-one! {:account-id 0} #(future (broken-function %)))))
  ;; don't let people replace needed keys
  (is (thrown? ExceptionInfo (p/paginate-one! {:idx 0} identity)))
  ;; don't let people replace needed keys
  (is (thrown? ExceptionInfo (p/paginate! identity {} [{:idx 0}]))))
