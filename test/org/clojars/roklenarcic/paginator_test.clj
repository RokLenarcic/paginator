(ns org.clojars.roklenarcic.paginator-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p])
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
      (is (= (repeat 10 []) (p/paginate! (p/async-fn get-pages 5) {} (map #(assoc nil :id %) (range 10)))))
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
  (mapv #(assoc % :account-id acc-id) (projects-of-accounts acc-id)))

(defn ret-for-acc-pages
  "Helper fn"
  [acc-id]
  (let [ps (ret-for-acc acc-id)]
    (map #(subvec ps % (+ 2 %)) (range 0 (count ps) 2))))

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
           (vec (p/paginate-one! {:account-id 0} projects-by-id {:pages? true}))))))

(deftest paginate-test
  (let [all-accounts (map #(assoc {} :account-id %) (range 10))
        all-ret (map ret-for-acc (range 10))
        all-pages (mapcat ret-for-acc-pages (range 10))
        sorted #(sort-by (comp (juxt :account-id :project) first) %)]
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
  (is (thrown? ExceptionInfo (p/paginate-one! {:account-id 0} #(future (broken-function %))))))


                #_(def accounts
  [{:account-name "A"} {:account-name "B"} {:account-name "C"} {:account-name "D"} {:account-name "E"} {:account-name "F"}])

#_(def repositories
  {"A" [{:repo-name "A/1"} {:repo-name "A/2"} {:repo-name "A/3"} {:repo-name "A/4"} {:repo-name "A/5"}]
   "B" []
   "C" [{:repo-name "C/1"}]
   "D" [{:repo-name "D/1"} {:repo-name "D/2"} {:repo-name "D/3"} {:repo-name "D/4"} {:repo-name "D/5"} {:repo-name "D/6"}]
   "E" [{:repo-name "E/1"} {:repo-name "E/2"}]
   "F" [{:repo-name "F/1"} {:repo-name "F/2"} {:repo-name "F/3"}]})


#_(defn get-accounts
  [{:keys [cursor add-page] :as s}]
  (let [resp (get-from-vector accounts cursor)]
    (cons (v-result s resp)
          (->> resp :body :items (map #(p/paging-state ::account-repos (:account-name %)))))))

#_(defn get-account-repos
  [{:keys [page-cursor id] :as s}]
  (v-result s (get-from-vector (repositories id) page-cursor)))

#_(deftest expanding-test
  (testing "expands into multiple paging states"
    (is (= [{:entity-type :x
             :id 1
             :items []
             :page-cursor 1
             :pages 1}
            (p/paging-state :account 1)
            (p/paging-state :account 2)
            (p/paging-state :account 3)
            (p/paging-state :account 5)]
           (p/merge-expand-result
             {:items [1 2 3 5]
              :page-cursor 1}
             (p/paging-state :x 1)
             (map #(p/paging-state :account %))))))
  (testing "multiple expand"
    (is (= [{:entity-type :x
             :id 1
             :items []
             :page-cursor nil
             :pages 1}
            (p/paging-state :account 5)
            (p/paging-state :account 6)
            {:entity-type :x
             :id 2
             :items []
             :page-cursor nil
             :pages 1}
            (p/paging-state :account 0)
            (p/paging-state :account 1)]
           (p/merge-expand-results
             [{:id 2 :entity-type :x :items [0 1]}
              {:id 1 :entity-type :x :items [5 6]}
              {:id 3 :entity-type :y :items [15 15]}]
             [(p/paging-state :x 1) (p/paging-state :x 2)]
             (map #(p/paging-state :account %)))))))

#_(deftest multi-entity-types-test
  (testing "multiple entity types"
    (is (= [{:entity-type ::account-repos
             :id "B"
             :items []
             :page-cursor nil
             :pages 1}
            {:entity-type ::account-repos
             :id "C"
             :items [{:repo-name "C/1"}]
             :page-cursor nil
             :pages 1}
            {:entity-type ::account-repos
             :id "E"
             :items [{:repo-name "E/1"}
                     {:repo-name "E/2"}]
             :page-cursor nil
             :pages 1}
            {:entity-type ::account-repos
             :id "A"
             :items [{:repo-name "A/1"}
                     {:repo-name "A/2"}
                     {:repo-name "A/3"}
                     {:repo-name "A/4"}
                     {:repo-name "A/5"}]
             :page-cursor nil
             :pages 3}
            {:entity-type ::account-repos
             :id "F"
             :items [{:repo-name "F/1"}
                     {:repo-name "F/2"}
                     {:repo-name "F/3"}]
             :page-cursor nil
             :pages 2}
            {:entity-type ::account-repos
             :id "D"
             :items [{:repo-name "D/1"}
                     {:repo-name "D/2"}
                     {:repo-name "D/3"}
                     {:repo-name "D/4"}
                     {:repo-name "D/5"}
                     {:repo-name "D/6"}]
             :page-cursor nil
             :pages 3}]
          (filter #(= ::account-repos (:entity-type %))
                  (p/paginate! (p/engine)
                               (fn [paging-states]
                                 (case (p/entity-type paging-states)
                                   ::accounts (get-accounts (first paging-states))
                                   ::account-repos (get-account-repos (first paging-states))))
                               [[::accounts nil]]))))))


