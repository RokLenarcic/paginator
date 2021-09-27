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
          parser (p/result-parser1
                   (constantly [])
                   (fn [{:keys [has-more?]}] has-more?)
                   (constantly nil))
          engine (p/engine
                   parser
                   (fn [_ _]
                     (when-not (.tryAcquire semaphore)
                       (throw (ex-info "Too many concurrent threads" {})))
                     (Thread/sleep 10)
                     (.release semaphore)
                     {:has-more? (< (swap! pages inc) 100)}))]
      (is (= (repeat 10 []) (p/paginate-coll! (p/with-concurrency engine 5) {} :any (range 10))))
      (is (thrown? Exception (p/paginate-coll! (p/with-concurrency engine 10) {} :any (range 100)))))))

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

(defmethod p/get-items
  ::accounts
  [params [{:keys [page-cursor]}]]
  (assoc (get-from-vector accounts page-cursor)
    :resp-type ::accounts))

(defmethod p/get-items
  ::account-repos
  [params [{:keys [page-cursor id]}]]
  (get-from-vector (repositories id) page-cursor))

(def e
  (p/engine
    (p/result-parser1
      (comp :items :body)
      (comp :offset :body)
      (fn [resp]
        (case (:resp-type resp)
          ::accounts (map #(p/paging-state ::account-repos (:account-name %)) (-> resp :body :items))
          nil)))))

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
          (filter #(= ::account-repos (:entity-type %)) (p/paginate! e {} [[::accounts nil]]))))))

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

(defmethod p/get-items :project-branches [params paging-states]
  (let [requested-page (:page-cursor (first paging-states) 0)]
    {:page requested-page
     :projects (get-in (branches (:auth-token params) (map :id paging-states) requested-page)
                       [:body :data :projects :nodes])}))

(defn extract-project
  "Separate project out, put it into the page-state map and finalize it by
  setting page-cursor to nil."
  [project]
  (-> (p/paging-state :project (:id project))
      (assoc :project (update project :repository dissoc :branchNames))
      (assoc :page-cursor nil)))

(def e2 (-> (p/engine
              (p/result-parser
                (fn [{:keys [projects]}]
                  (into {} (map (fn [{:keys [id repository]}] [[:project-branches id] (:branchNames repository)])) projects))
                (fn [{:keys [page projects]}]
                  (into {}
                        (map (fn [{:keys [id repository]}]
                               [[:project-branches id]
                                (when (>= (count (:branchNames repository)) branches-per-page)
                                  (inc page))]))
                        projects))
                (fn [{:keys [page projects]}]
                  (when (= 0 page) (mapv extract-project projects)))))
            (p/with-concurrency 5)
            (p/with-batcher false ids-per-page :page-cursor)))
