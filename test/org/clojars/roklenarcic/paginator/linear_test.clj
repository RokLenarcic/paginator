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

(defmethod p/get-items ::projects
  [{:keys [auth-token]} states]
  (get-projects auth-token (-> states first :page-cursor)))

(defmethod p/get-items ::projects-offset
  [{:keys [auth-token]} states]
  (get-projects-with-offset auth-token (-> states first :page-cursor)))

(deftest continuation-token-test
  (is (= projects
         (p/paginate-one!
           (p/engine (p/result-parser1
                       (comp :items :body)
                       #(get-in % [:headers "x-ms-continuationtoken"])))
           {:auth-token "MY AUTH"} ::projects nil))))

(deftest offset-test
  (is (= projects
         (p/paginate-one!
           (p/engine (p/result-parser1
                       (comp :items :body)
                       (comp :offset :body)))
           {:auth-token "MY AUTH"} ::projects-offset nil))))


(defn api-call [auth-token method url params]
  (let [offset (or (some->> url (re-find #"\?offset=(.*)") second Long/parseLong)
                   0)]
    {:body {:values (take 2 (drop offset projects))
            :next (when (< (+ 2 offset) (count projects))
                    (str "/projects?offset=" (+ 2 offset)))}}))

(def paged-api
  (p/engine
    (p/result-parser1 (comp :values :body) (comp :next :body))
    (fn [[auth-token method url params] paging-states]
      (if-let [cursor (-> paging-states first :page-cursor)]
        (api-call auth-token :get cursor {})
        (api-call auth-token method url params)))))

(deftest api-call-test
  (is (= projects
         (p/paginate-one! paged-api ["X" :get "/projects" {}] ::projects2 nil))))
