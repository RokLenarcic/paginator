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
                      (p/merge-result s {:page-cursor (< (swap! pages inc) 100)}))]
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
  (p/merge-result s {:page-cursor (-> v :body :offset) :items (-> v :body :items)}))

(defn get-accounts
  [{:keys [page-cursor] :as s}]
  (let [resp (get-from-vector accounts page-cursor)]
    (cons (v-result s resp)
          (->> resp :body :items (map #(p/paging-state ::account-repos (:account-name %)))))))

(defn get-account-repos
  [{:keys [page-cursor id] :as s}]
  (v-result s (get-from-vector (repositories id) page-cursor)))

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
    (p/merge-results paging-states results)))

(def e2 (-> (p/engine)
            (p/with-concurrency 5)
            (p/with-batcher false ids-per-page :page-cursor)))

(def ids
  ["gid://gitlab/Project/28743567"
   "gid://gitlab/Project/29397853"
   "gid://gitlab/Project/29397862"
   "gid://gitlab/Project/27665843"
   "gid://gitlab/Project/27665843"
   "gid://gitlab/Project/28285063"
   "gid://gitlab/Project/29917354"
   "gid://gitlab/Project/29917249"
   "gid://gitlab/Project/29917714"
   "gid://gitlab/Project/29917276"
   "gid://gitlab/Project/29902178"
   "gid://gitlab/Project/29917139"
   "gid://gitlab/Project/29917704"
   "gid://gitlab/Project/29917360"
   "gid://gitlab/Project/29917131"
   "gid://gitlab/Project/29917284"
   "gid://gitlab/Project/29918445"
   "gid://gitlab/Project/29917799"
   "gid://gitlab/Project/29917878"
   "gid://gitlab/Project/29917945"
   "gid://gitlab/Project/29918078"
   "gid://gitlab/Project/29918167"
   "gid://gitlab/Project/29918202"
   "gid://gitlab/Project/29918240"
   "gid://gitlab/Project/29918309"
   "gid://gitlab/Project/29918467"
   "gid://gitlab/Project/29918528"
   "gid://gitlab/Project/29917802"
   "gid://gitlab/Project/29917884"
   "gid://gitlab/Project/29917955"
   "gid://gitlab/Project/29918096"
   "gid://gitlab/Project/29918169"
   "gid://gitlab/Project/29918206"
   "gid://gitlab/Project/29918245"
   "gid://gitlab/Project/29918311"
   "gid://gitlab/Project/29918474"
   "gid://gitlab/Project/29918533"
   "gid://gitlab/Project/29917806"
   "gid://gitlab/Project/29917890"
   "gid://gitlab/Project/29917962"
   "gid://gitlab/Project/29918115"
   "gid://gitlab/Project/29918173"
   "gid://gitlab/Project/29918207"
   "gid://gitlab/Project/29918253"
   "gid://gitlab/Project/29918316"
   "gid://gitlab/Project/29918487"
   "gid://gitlab/Project/29918539"
   "gid://gitlab/Project/29917846"
   "gid://gitlab/Project/29917895"
   "gid://gitlab/Project/29917966"
   "gid://gitlab/Project/29918127"
   "gid://gitlab/Project/29918175"
   "gid://gitlab/Project/29918211"
   "gid://gitlab/Project/29918258"
   "gid://gitlab/Project/29918325"
   "gid://gitlab/Project/29918491"
   "gid://gitlab/Project/29918548"
   "gid://gitlab/Project/29917849"
   "gid://gitlab/Project/29917901"
   "gid://gitlab/Project/29917970"
   "gid://gitlab/Project/29918134"
   "gid://gitlab/Project/29918178"
   "gid://gitlab/Project/29918216"
   "gid://gitlab/Project/29918264"
   "gid://gitlab/Project/29918340"
   "gid://gitlab/Project/29918495"
   "gid://gitlab/Project/29918556"
   "gid://gitlab/Project/29917851"
   "gid://gitlab/Project/29917904"
   "gid://gitlab/Project/29917979"
   "gid://gitlab/Project/29918138"
   "gid://gitlab/Project/29918180"
   "gid://gitlab/Project/29918220"
   "gid://gitlab/Project/29918270"
   "gid://gitlab/Project/29918349"
   "gid://gitlab/Project/29918499"
   "gid://gitlab/Project/29918560"
   "gid://gitlab/Project/29917867"
   "gid://gitlab/Project/29917909"
   "gid://gitlab/Project/29917990"
   "gid://gitlab/Project/29918142"
   "gid://gitlab/Project/29918184"
   "gid://gitlab/Project/29918222"
   "gid://gitlab/Project/29918278"
   "gid://gitlab/Project/29918356"
   "gid://gitlab/Project/29918503"
   "gid://gitlab/Project/29918563"
   "gid://gitlab/Project/29917870"
   "gid://gitlab/Project/29917914"
   "gid://gitlab/Project/29918007"
   "gid://gitlab/Project/29918147"
   "gid://gitlab/Project/29918188"
   "gid://gitlab/Project/29918223"
   "gid://gitlab/Project/29918282"
   "gid://gitlab/Project/29918365"
   "gid://gitlab/Project/29918507"
   "gid://gitlab/Project/29918567"
   "gid://gitlab/Project/29917873"
   "gid://gitlab/Project/29917932"
   "gid://gitlab/Project/29918015"
   "gid://gitlab/Project/29918150"
   "gid://gitlab/Project/29918194"
   "gid://gitlab/Project/29918230"
   "gid://gitlab/Project/29918291"
   "gid://gitlab/Project/29918373"
   "gid://gitlab/Project/29918512"
   "gid://gitlab/Project/29918574"
   "gid://gitlab/Project/29917875"
   "gid://gitlab/Project/29917937"
   "gid://gitlab/Project/29918066"
   "gid://gitlab/Project/29918160"
   "gid://gitlab/Project/29918197"
   "gid://gitlab/Project/29918232"
   "gid://gitlab/Project/29918294"
   "gid://gitlab/Project/29918387"
   "gid://gitlab/Project/29918517"
   "gid://gitlab/Project/29918577"
   ])

(comment
  (p/paginate!
    e2
    #(project-branches "30639a3add850f6a35120e86f66f38247c99fd8abb0617ff5a6ad6845ba8b51c" %)
    (map #(vector :project-branches %) ids)))
