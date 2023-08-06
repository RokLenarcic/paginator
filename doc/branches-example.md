# Example: Listing branches via GitLab GraphQL

You're trying to list all branches for 200+ repositories by id via GitLab GraphQL API

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
  (let [page (:cursor (first paging-states) 0)
        b (branches auth-token (map :id paging-states) page)
        nodes (group-by :id (get-in b [:body :data :projects :nodes]))]
    (mapv (fn [{:keys [add-page id]}]
            (let [{:keys [repository]} (first (nodes id))]
              (add-page (:branchNames repository)
                        (when (>= (count (:branchNames repository)) branches-per-page)
                          (inc page))
                        {:project (update node :repository dissoc :branchNames)})))
          paging-states)))

(p/paginate! (p/async-fn #(project-branches "521aaxxxxxx7befc7828" %) 5)
             {:batcher (p/grouped-batcher :cursor ids-per-page)}
             ids)
```

### project-branches

It returns updated paging-states collection, with extra `:project` key with all the project related information

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
 {:id "gid://gitlab/Project/29917873",
  :pages 1,
  :items ["master"],
  :cursor nil,
  :project {:id "gid://gitlab/Project/29917873",
            :name "Awesome Hacking",
            :visibility "private",
            :httpUrlToRepo "https://gitlab.com/rtg41000/group8/Awesome-Hacking.git",
            :fullPath "rtg41000/group8/Awesome-Hacking",
            :namespace {:id "gid://gitlab/Group/13505299"},
            :repository {:rootRef "master"}}}
 {:id "gid://gitlab/Project/29917932",
  :pages 1,
  :items ["dependabot/npm_and_yarn/socket.io-2.4.1"
          "gg/docz"
          "gh-pages"
          "cp/remove-inner-shadow-on-focused-inputs"
          "next"
          "ad/tsconfig-flag"
          "ad/reduce-find-dom-node"
          "develop"
          "excavator/policy-bot-oss-shadow"
          "al/motion"
          "johnlee/modern-colors-non-core"
          "release/1.x"
          "release/2.x"
          "ad/fix-classes-constants-rule"
          "mw/dateinput-cal-focus"
          "ad/fix-webpack"
          "release/3.17.x"
          "ad/form-component"
          "tm/css-variables"
          "dependabot/npm_and_yarn/postcss-8.2.10"
          "v4"
          "ad/popover2props-compatibility"
          "query-list-create-item"],
  :cursor nil,
  :project {:id "gid://gitlab/Project/29917932",
            :name "Blueprint",
            :visibility "private",
            :httpUrlToRepo "https://gitlab.com/rtg41000/group8/blueprint.git",
            :fullPath "rtg41000/group8/blueprint",
            :namespace {:id "gid://gitlab/Group/13505299"},
            :repository {:rootRef "develop"}}}
  ....
```

We can then reconstruct the expected final data shape easily from such maps.
