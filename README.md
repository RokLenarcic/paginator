# Paginator

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/paginator.svg)](https://clojars.org/org.clojars.roklenarcic/paginator)

A lot of APIs solve the problem with large lists by paging. A complex scenario where you want
to load all pages of one entity, then loading all pages of another type of entity, concurrently,
while also limiting max concurrency, is not an easy thing to code. This is why this library exists.

You interact with this library by importing `[org.clojars.roklenarcic.paginator :as p]`.

Examples are at the end.

## Pagination state

This library uses a core async loop that takes paging state maps via input channel
and places these maps with items on the output channel.

You create a paging state for an entity by calling `p/paging-state` function:

```clojure
(p/paging-state :entity-type 1)
```

This creates: 

```clojure
(paging-state :customer 1)
=> {:id 1, :entity-type :customer, :pages 0, :items []}
```

Pages of items are concatenated into `:items` vector. 

**After the first request there will also be a key `:page-cursor`. If page-cursor is present but `nil` the
paging is considered finished and the state map will be placed into the output channel.**

## Paginate

The pagination is done via `p/paginate*!`. It takes an `engine` and `get-pages-fn`. Engine will be
described later. **The pages fn should be a function that takes a collection of paging states (as per engine's batching settings), does whatever
is necessary to load the data for them, then return a new collection of paging states (or a single paging state).**

Usually that means performing a request of some sort, updating the `:items` vector in each paging state with additional
items, and updating `:page-cursor` (or setting it to `nil` if done). As the default batching size is `1`, the input
collection of paging-state will usually have just one item.

**If is possible return additional paging-states, thereby introducing additional entities to page. Same for removing
paging-states.**

```clojure
(let [{:keys [out in]} (p/paginate*! engine (fn [[paging-state]]
                                              ...
                                              (-> paging-state
                                                  (update :items into (more-stuff))
                                                  (assoc :page-cursor next-page))))]
  (async/onto-chan! in paging-states)
  ...)
```

**If input channel is not closed the paginate will never terminate.** Out channel will
get paging states with `:items` and potentially `:exception`, an exception that happened that fetch call.

The reason why `in` and `out` channels are used is because you can use them to chain together
multiple pagination processes.

### Merging helpers

To help with merging your new data with existing paging states there are helpers provided:

- `merge-result` merges  one map of new data with one paging-state
- `merge-results` merges a collection of maps with new data with a collection of paging states 

The function that merges collections will update data by matching new data maps with existing
paging-states by matching `:entity-type` and `:id`. Any paging-states without a matching new data
map will be marked done by setting `:page-cursor` to nil.

The merge itself follows the rules:
- `:items` key is merged by concatenating
- `:pages` is always increased by 1
- `:page-cursor` is always set whether the incoming data has the key or not
- the rest of the keys are merged into paging state as is

Let's rewrite previous example with this helper:

```clojure
(let [{:keys [out in]} (p/paginate*! engine (fn [[paging-state]]
                                              ...
                                              (p/merge-state paging-state {:items (more-stuff)
                                                                           :page-cursor next-page})))]
  (async/onto-chan! in paging-states)
  ...)
```

### Paging helper

Instead of using channels directly, you can also use one of the convenience wrappers that 
will put you input onto input channel as paging states and block and collect output paging states into a collection.

To do that, use one of the convenience wrappers:

### paginate!

This wrapper adds to base `paginate*!`:
- takes a coll of entity IDs and creates page states for you
- takes a coll and pushes page states into the input channel and closes it
- blockingly reads the result from the output channel into a collection of page states
- throws an exception if a page state has an exception

```clojure
(let [states (paginate! engine
                        get-pages-fn 
                        [[:projects-by-person 1] 
                         [:projects-by-person 2] 
                         [:projects-by-person 3]])]
  states)
```

### paginate-coll!

This wrapper does even more than `paginate!` by making more assumptions about what you have and
what you want. Besides what `paginate!` does, this also:

- takes a single entity type and a coll of IDs (i.e. assumes all your entities are of the same type)
- it guarantees that the order of outputs matches the order of input IDs
- it will not return any new entities returned only the initial ones
- it will return a vector of vectors, where the inner vector is the resulting `:items` from each final paging state

### paginate-one!

This wrapper is like `paginate-coll` but it operates on a single paging state and returns a single vector of results.

Instead of `get-pages-fn` it takes `get-page-fn`, a function that will take a single paging state and return a single
updated paging state.

## Pagination engine

An engine is a map that describes aspects of your paging process.

```clojure
(p/engine)
(p/engine runner-fn)
```

## runner-fn

This optional parameter is a function of 2 args that takes a no-arg function and a result-ch. It should run
the function, usually in some async manner and push the result into result-ch.

Defaults to `clojure.core/future-call` + blocking core.async push. You can use this to provide an alternative threadpools
or to use core.async directly (use go-block).

## with-batcher

Sets batching configuration for the engine. As paging states are queued up for processing they are
arranged into batches. The `get-pages-fn` will be called with one such batch each time.

Each paging state queued up for (more) processing will be evaluated with `batch-fn` which should return
the batch ID for the paging stage. It will be added to that batch. 

If batch reached maximum size for batches it will enter processing. If 100ms has passed since
any event in the system one of the unfinished batches will enter processing if there's unused
concurrency in the engine. If a sorted batcher is chosen then the unfinished batch that will enter
processing will be the one with the lowest ID. In case of a sorted batcher, the IDs must be comparable.

The parameters are:
- sorted
- max-items (defaults to 1)
- batch-fn (defaults to :entity-type)

## with-result-buf

Sets a different buffer size on output channel, the default being 100.

## with-concurrency

Sets maximum concurrency for paged get-pages calls. Engine will not queue additional
`get-pages-fn` calls if maximum concurrency is reached. Default is 1.

## Linear pagination with next page links

A common scenario is to request a list from an API and you get in the response some kind of token,
to use to request the next page. This usually precludes any parallelization.

E.g. https://docs.microsoft.com/en-us/rest/api/azure/devops/core/projects/list?view=azure-devops-rest-6.1

In the result you'll get `x-ms-continuationtoken` header. Provide it when calling the list endpoint in
`continuationToken` query parameter.

This is a fairly common scenario in paging results.

If `p` is required as `org.clojars.roklenarcic.paginator`.

```clojure
(p/paginate-one! (p/engine)
                 (fn [{:keys [page-cursor] :as s}]
                   (p/merge-result
                     (let [resp (get-projects "MY AUTH" page-cursor)]
                       {:page-cursor (get-in resp [:headers "x-ms-continuationtoken"])
                        :items (-> resp :body :items)})
                     s)))

```

## Offset + count

A common pattern is that API endpoint takes an `offset` parameter (also called `skip` sometimes)
and a `count` (or `page-size`) parameter. It then returns `count` items from `offset` onward.

The offset itself can be a number of items or a number of pages. It doesn't make a difference to a paging algorithm. 

```clojure
(p/paginate-one!
  (p/engine)
  (fn [{:keys [page-cursor] :as s}]
    (p/merge-result
      (let [resp (get-projects-with-offset "MY AUTH" page-cursor)]
        {:page-cursor (get-in resp [:body :offset])
         :items (get-in resp [:body :items])})
      s)))
```

## Making a more generic get-pages-fn function

In the previous examples, the function used was specific to a callsite. But sometimes the
paging and items logic is general for most API calls, and we want to factor that out.

What we need is a function that will return a function.

Imagine you're working with Bitbucket REST API. Some API calls will
return a body with two keys `:values` and `:next` as a way of pagination. And there are many such
functions, so we want avoid repetition.

```clojure
(defn api-call [auth-token method url params]
  ...)

(defn api-caller
  [auth-token method url params]
  (fn [{:keys [page-cursor] :as s}]
    (p/merge-result
      (if page-cursor
        (api-call auth-token :get page-cursor {})
        (api-call auth-token method url params))
      s)))

(p/paginate-one!
  (p/engine)
  (api-caller "X" :get "/projects" {}))
```

## Emitting new entities or paging states during the process

Imagine you paginate accounts, and then you want to paginate users, and then paginate their emails.
You want to generate new paging states for accounts and return them into process. But for emails you want to
return them into the results stream. **This won't work with `paginate-one!` and `paginate-coll!` because
those assume that the input and output entities are the same**.

It is trivial to add extra data to paging states and to create new paging states for paginating on subelements.

Here's an example from tests:

```clojure
;; mock data
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

(p/paginate! (p/engine)
             (fn [paging-states]
               (case (p/entity-type paging-states)
                 ::accounts (get-accounts (first paging-states))
                 ::account-repos (get-account-repos (first paging-states))))
             [[::accounts nil]])
=>
[{:id "B", :entity-type ::account-repos, :pages 1, :items [], :page-cursor nil}
 {:id nil,
  :entity-type ::accounts,
  :pages 3,
  :items [{:account-name "A"}
          {:account-name "B"}
          {:account-name "C"}
          {:account-name "D"}
          {:account-name "E"}
          {:account-name "F"}],
  :page-cursor nil}
 {:id "C",
  :entity-type ::account-repos,
  :pages 1,
  :items [{:repo-name "C/1"}],
  :page-cursor nil}
 {:id "E",
  :entity-type ::account-repos,
  :pages 1,
  :items [{:repo-name "E/1"} {:repo-name "E/2"}],
  :page-cursor nil}
 {:id "A",
  :entity-type ::account-repos,
  :pages 3,
  :items [{:repo-name "A/1"} {:repo-name "A/2"} {:repo-name "A/3"} {:repo-name "A/4"} {:repo-name "A/5"}],
  :page-cursor nil}
 {:id "F",
  :entity-type ::account-repos,
  :pages 2,
  :items [{:repo-name "F/1"} {:repo-name "F/2"} {:repo-name "F/3"}],
  :page-cursor nil}
 {:id "D",
  :entity-type ::account-repos,
  :pages 3,
  :items [{:repo-name "D/1"}
          {:repo-name "D/2"}
          {:repo-name "D/3"}
          {:repo-name "D/4"}
          {:repo-name "D/5"}
          {:repo-name "D/6"}],
  :page-cursor nil}]

```

## A more complex example

[Listing branches via GitLab GraphQL API](doc/branches-example.md)

## Exceptions

As the paging proceeds concurrently more than 1 exception can arise before the process is stopped.

Calls to `paginate*!` return exceptions as `:exception` key in paging-stages. More high level calls
such as `paginate!`, `paginate-coll!`, `paginate-one!` will throw the exceptions found. Because there can
be multiple, one a single `java.util.concurrent.ExecutionException` will be thrown with first exception
found as cause and the rest added to suppressed exception list. See `Throwable.getSuppressed`.



# License

Copyright © 2021 Rok Lenarčič

Licensed under the term of the Eclipse Public License - v 2.0, see LICENSE.
