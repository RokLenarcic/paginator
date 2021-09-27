# Example: Listing branches via GitLab GraphQL

You're trying to list all branches for 1000+ repositories by id via GitLab GraphQL API

The API won't let you ask for more than 100 repositories at once, and the query complexity
limit in this case brings that number down to 35. Each of these repositories
might have more than a thousand branches, so you need paging there too. And we would like
to be efficient with the number of requests.

Unfortunately it is impossible to do this directly. A GraphQL query looks like this:

```
projects(first: 35, 
         ids: ["gid://gitlab/Project/28743567", "gid://gitlab/Project/29397853", ...],
         membership: true) {
    pageInfo { hasNextPage, endCursor }
    nodes {
        id, name, visibility, httpUrlToRepo, fullPath
        namespace {
         id
        }
        repository { rootRef,
            branchNames(limit: 100, offset: 0, searchPattern: "*" )
        }
    }
}
```

We have two levels of paging here:
- projects are paged via `first` + `IDs` combo, which is can load up to 35 project by IDs. So if we have more than 35 ids, we need to do this in multiple queries
- branches are paged via `offset` + `limit` combo. So we can only request a specific page, but same page for all of the IDs listed

To be efficient with our requests we must therefore batch IDs that have e.g. third page available
with each other.

We want to have a result that has information about the project + list of branches.

The response to that query looks like this:

```clojure
:body {:data {:projects {:pageInfo {:hasNextPage false, :endCursor "eyJpZCI6IjI3NjY1ODQzIn0"},
                          :nodes [{:id "gid://gitlab/Project/29918528",
                                   :name "Tinygo",
                                   :visibility "private",
                                   :httpUrlToRepo "https://gitlab.com/rtg41000/group1/tinygo.git",
                                   :fullPath "rtg41000/group1/tinygo",
                                   :namespace {:id "gid://gitlab/Group/13505290"},
                                   :repository {:rootRef "release",
                                                :branchNames ["cgo-picolibc-stdio"
                                                              "avoid-usb-heap-alloc"
                                                              "circle-ci-next-gen-images"
                                                              "builder-rwmutex"
                                                              "staging"
                                                              "shadowstack"
                                                              "test-other-architectures"
                                                              "esp32-i2c"
                                                              ...]}}
.....
```

Here's some code:

```clojure
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
            (p/with-concurrency 1)
            (p/with-batcher false ids-per-page :page-cursor)))


(p/paginate!
  e2
  {:auth-token "521aaxxxxxx7befc7828"}
  (map #(vector :project-branches %) ids))
```

### get-items

The get-items function returns what the GitLab returns, but it also returns which page was requested, because GitLab's
response doesn't indicate that at all. So we help ourselves by adding current page requested to the response ourselves.

### Result parser, items-fn

You can see that items-fn returns just a straight-forward map of `[:project-branches project-id]` -> branches.

### Result parser, cursor-fn

If the number of returned branches matches the maximum then there are more pages, so we return `(inc page)` for the cursor.

### Result parser, new-entities-fn

This is where we "export" project information separated from branches lists. When loading page 0 we export
returned project data (sans branches) as a new paging state with `:project` entity type. **We set `:page-cursor`
to `nil` to indicate that no further processing is needed**.

### with-concurrency

In the example the concurrency is 1. On my sample of 117 projects with various amount of branches this produces the following requests:

- Request for 35 projects, page 0 of branches
- Request for 35 projects, page 0 of branches
- Request for 35 projects, page 0 of branches
- Request for 12 projects, page 0 of branches
- Request for 22 projects, page 1 of branches
- Request for 11 projects, page 2 of branches

This is obviously the least number of requests needed to page them all. If I make the concurrency 5 I get:

- Request for 35 projects, page 0 of branches
- Request for 35 projects, page 0 of branches
- Request for 35 projects, page 0 of branches
- Request for 12 projects, page 0 of branches
- Request for 3 projects, page 1 of branches
- Request for 5 projects, page 1 of branches
- Request for 7 projects, page 1 of branches
- Request for 1 projects, page 2 of branches
- Request for 3 projects, page 2 of branches
- Request for 4 projects, page 2 of branches
- Request for 7 projects, page 1 of branches
- Request for 3 projects, page 2 of branches

This is less ideal, but it keeps making requests every 100ms if there's spare concurrency. The actual wall clock time
is twice lower than when concurrency is 1.

### Result

```clojure
[....
 {:id "gid://gitlab/Project/29918178",
  :entity-type :project-branches,
  :pages 1,
  :items ["edit-form-flicker-bug"
          "fix-default-sort-order"
          "feature/sql-nested-queries"
          "develop"
          "feature/form-create"
          "datetime-fixes-and-extensions"
          "sandbox"
          "title-fn-fix"
          "bugfix-read-only-lambda"
          "style-fix"
          "additional-date-utils"],
  :page-cursor nil},
 {:id "gid://gitlab/Project/28285063",
  :entity-type :project,
  :pages 0,
  :items [],
  :project {:id "gid://gitlab/Project/28285063",
            :name "A public project",
            :visibility "private",
            :httpUrlToRepo "https://gitlab.com/rok.lenarcic1/a-public-project.git",
            :fullPath "rok.lenarcic1/a-public-project",
            :namespace {:id "gid://gitlab/Namespace/12788889"},
            :repository {:rootRef "main"}},
  ....
```

We can then reconstruct the expected final data shape easily from such maps.
