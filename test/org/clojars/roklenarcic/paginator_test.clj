(ns org.clojars.roklenarcic.paginator-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p]
            [clj-http.client :as h]
            [cheshire.core :as json])
  (:import (java.util.concurrent Semaphore)))

(deftest concurrency-test
  (testing "concurrency is respected"
    (let [pages (atom 0)
          semaphore (Semaphore. 5)
          engine (p/engine)
          get-pages (fn [[s]]
                      (when-not (.tryAcquire semaphore)
                        (throw (ex-info "Too many concurrent threads" {})))
                      (Thread/sleep 10)
                      (.release semaphore)
                      (p/merge-result {:page-cursor (< (swap! pages inc) 100)} s))]
      (is (= (repeat 10 []) (p/paginate-coll! (p/with-concurrency engine 5) get-pages :any (range 10))))
      (is (thrown? Exception (p/paginate-coll! (p/with-concurrency engine 10) get-pages :any (range 100)))))))

(def accounts
  [{:account-name "A"} {:account-name "B"} {:account-name "C"} {:account-name "D"} {:account-name "E"} {:account-name "F"}])

(def repositories
  {"A" [{:repo-name "A/1"} {:repo-name "A/2"} {:repo-name "A/3"} {:repo-name "A/4"} {:repo-name "A/5"}]
   "B" []
   "C" [{:repo-name "C/1"}]
   "D" [{:repo-name "D/1"} {:repo-name "D/2"} {:repo-name "D/3"} {:repo-name "D/4"} {:repo-name "D/5"} {:repo-name "D/6"}]
   "E" [{:repo-name "E/1"} {:repo-name "E/2"}]
   "F" [{:repo-name "F/1"} {:repo-name "F/2"} {:repo-name "F/3"}]})

(defn get-from-vector [v cursor]
  (let [p (or cursor 0)]
    {:body {:items (take 2 (drop p v))
            :offset (when (< (+ 2 p) (count v))
                      (+ 2 p))}}))

(defn v-result [s v]
  (p/merge-result {:page-cursor (-> v :body :offset) :items (-> v :body :items)} s))

(defn get-accounts
  [{:keys [page-cursor] :as s}]
  (let [resp (get-from-vector accounts page-cursor)]
    (cons (v-result s resp)
          (->> resp :body :items (map #(p/paging-state ::account-repos (:account-name %)))))))

(defn get-account-repos
  [{:keys [page-cursor id] :as s}]
  (v-result s (get-from-vector (repositories id) page-cursor)))

(deftest expanding-test
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

(deftest multi-entity-types-test
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

(def ids-per-page 35)
(def branches-per-page 100)

(defn branches [auth-token ids page]
  (let [q (format "query {
                            projects(first: %s,
                                     ids: %s,
                                     membership: true) {
                                         pageInfo { hasNextPage, endCursor }
                                         nodes {
                                           id, name, visibility, httpUrlToRepo, fullPath
                                           namespace {
                                             id
                                           }
                                           repository { rootRef, branchNames(limit: %s, offset: %s, searchPattern: \"*\" )}
                                         }
                            }}"
                  (count ids)
                  (json/generate-string ids)
                  branches-per-page
                  (* branches-per-page page))]
    (h/request
      {:url "https://gitlab.com/api/graphql"
       :content-type :json
       :as :json
       :oauth-token auth-token
       :request-method :post
       :form-params {:query q}})))

(defn project-branches [auth-token paging-states]
  (let [page (:page-cursor (first paging-states) 0)
        b (branches auth-token (map :id paging-states) page)
        nodes (get-in b [:body :data :projects :nodes])
        results (mapv (fn [{:keys [id repository] :as node}]
                        {:id id
                         :entity-type :project-branches
                         :project (update node :repository dissoc :branchNames)
                         :page-cursor (when (>= (count (:branchNames repository)) branches-per-page)
                                        (inc page))
                         :items (:branchNames repository)})
                      nodes)]
    (p/merge-results results paging-states)))

(def e2 (-> (p/engine)
            (p/with-concurrency 5)
            (p/with-batcher false ids-per-page :page-cursor)))

(comment
  (p/paginate!
    e2
    #(project-branches "" %)
    (map #(vector :project-branches %) ids)))
