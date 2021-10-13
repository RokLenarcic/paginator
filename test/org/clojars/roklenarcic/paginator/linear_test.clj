(ns org.clojars.roklenarcic.paginator.linear-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.paginator :as p]))

(def projects [{:id 1 :name "P1"}
               {:id 2 :name "P2"}
               {:id 3 :name "P3"}
               {:id 4 :name "P4"}
               {:id 5 :name "P5"}
               {:id 6 :name "P6"}
               {:id 7 :name "P7"}
               {:id 8 :name "P8"}
               {:id 9 :name "P9"}
               {:id 10 :name "P10"}])

(defn get-projects [auth-token continuation-token]
  (let [items (cond->> projects
                continuation-token (drop-while #(not= continuation-token (:id %))))]
    {:headers {"x-ms-continuationtoken" (-> (drop 2 items) first :id)}
     :body {:items (take 2 items)}}))

(defn get-projects-with-offset [auth-token offset]
  (let [offset (or offset 0)]
    {:body {:items (take 2 (drop offset projects))
            :offset (when (< (+ 2 offset) (count projects))
                      (+ 2 offset))}}))

(deftest continuation-token-test
  (is (= projects
         (p/paginate-one! (p/engine)
                          (fn [{:keys [page-cursor] :as s}]
                            (p/merge-result
                              (let [resp (get-projects "MY AUTH" page-cursor)]
                                {:page-cursor (get-in resp [:headers "x-ms-continuationtoken"])
                                 :items (-> resp :body :items)})
                              s))))))

(deftest offset-test
  (is (= projects
         (p/paginate-one!
           (p/engine)
           (fn [{:keys [page-cursor] :as s}]
             (p/merge-result
               (let [resp (get-projects-with-offset "MY AUTH" page-cursor)]
                 {:page-cursor (get-in resp [:body :offset])
                  :items (get-in resp [:body :items])})
               s))))))


(defn api-call [auth-token method url params]
  (let [offset (or (some->> url (re-find #"\?offset=(.*)") second Long/parseLong)
                   0)]
    {:page-cursor (when (< (+ 2 offset) (count projects))
                    (str "/projects?offset=" (+ 2 offset)))
     :items (take 2 (drop offset projects))}))

(defn api-caller
  [auth-token method url params]
  (fn [{:keys [page-cursor] :as s}]
    (p/merge-result
      (if page-cursor
        (api-call auth-token :get page-cursor {})
        (api-call auth-token method url params))
      s)))

(deftest api-call-test
  (is (= projects
         (p/paginate-one!
           (p/engine)
           (api-caller  "X" :get "/projects" {})))))
