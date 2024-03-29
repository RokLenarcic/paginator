# Paginator

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/paginator.svg)](https://clojars.org/org.clojars.roklenarcic/paginator)

You interact with this library by importing `[org.clojars.roklenarcic.paginator :as p]`.

## Paginating one item

```clojure

(defn account-projects [{:keys [account-id add-page cursor]}]
  (let [{:keys [items offset]} (:body (get-account-projects-by-id account-id cursor))]
    (add-page items offset)))

(p/paginate-one! {:account-id 0} account-projects)
=>
#p/PagingState{:items [{:project 0, :account 0}
                       {:project 1, :account 0}
                       {:project 2, :account 0}
                       {:project 3, :account 0}
                       {:project 4, :account 0}
                       {:project 5, :account 0}
                       {:project 6, :account 0}
                       {:project 7, :account 0}
                       {:project 8, :account 0}
                       {:project 9, :account 0}],
               :cursor nil,
               :pages 5,
               :add-page nil,
               :idx 0,
               :account-id 0}
```

Any input that is not map is converted into `{:id value}`.

The input map is merged into PagingState record with record fields of:
- cursor (last returned cursor, starts with nil)
- pages
- items (starts empty)
- add-page (return helper function)
- idx (used to keep order of results in async settings)

Your function will be called with this record iteratively to load more pages. Use your input properties
and cursor to make loads. **Don't have these keys in your input map.**

When returning, you can use convenience function `add-page`, which is fn that will
return PagingState updated with more items and the new cursor. It has multiple arities:
- `(fn [additional-items])`, next cursor is nil, stops paging
- `(fn [additional-items next-cursor])`
- `(fn [additional-items next-cursor extra-properties])` will add more properties to PagingState

Also see [result unwrapping](#result-unwrapping)

## Async execution

The `run-fn` function you provide can, of course, do work in a future, a thread-pool or using callbacks. Paginator supports
such mechanisms by allowing `run-fn` to return a Future or some IPending (promise) instead of normal return.

A convenience function is provided that will wrap any function in invocation of `future` with concurrency limited:

```clojure
(def my-async-fn (p/async-fn my-fn 5))
;; you can pass a function created by async-fn to another as concurrency parameter
;; in that case the concurrency limit will be shared among the functions
(def another-async-fn (p/async-fn another-fn my-async-fn))
```

## Paginating a collection of items

We can submit multiple items to load pages for, in this case the function returns
*a lazy sequence of finished PagingStates as they are finished*. 

Your function can return a PagingState or another value or a collection of these items, or a Future returning one of these.

```clojure
(p/paginate! account-projects {} [{:account-id 1} {:account-id 2}])
```

We get *a lazy sequence* of finished PagingStates as they become available:

```clojure
(#p/PagingState{:items [{:project 10, :account 1} ... {:project 19, :account 1}],
                :cursor nil, :pages 5, :add-page nil, :idx 0, :account-id 1}
 #p/PagingState{:items [{:project 20, :account 2} ... {:project 29, :account 2}],
                :cursor nil, :pages 5, :add-page nil, :idx 0, :account-id 2})
```

Order from input is preserved.

### Batching

You can specify a batcher as an option. **Having a batcher will change the function signature of `run-fn`, it should
expect a coll of PagingStates.

```clojure
(p/paginate! account-projects {:batcher 5} items)
```

This will produce batches of five items. Instead of specifying a number you can provide an instance of Batcher protocol,
such as grouped batcher:

```clojure
;; same as providing just a number
(p/paginate! account-projects {:batcher (p/batcher 5)} items)
;; batches are created from items with same property of PagingItem
(p/paginate! account-projects {:batcher (p/grouped-batcher :pages 5)} items)
```

The strategy parameter refers to strategy when dealing with [partial batches](#partial-batches), one possible value
is `:min-batches` which will produce the fewest batches possible.

### Result unwrapping

A helper function `p/unwrap` is provided which will unwrap PagingState, returning vector of its items, merging into each
item extra properties from enclosing PagingState. This is only possible if items are maps, so `p/unwrap` will throw if
items are not maps.

### Injecting additional items

You can return another value instead of PagingState from your function, any such return will be converted to PagingState 
and queued, same as inputs.

### Returning pages

You can instruct pager to return each page as it's loaded to avoid having to load all pages before you can start processing results.

Supply `:pages? true` in options.

## Partial batches

If you have a batcher that has batch size of 10, and you have 33 items to paginate, then you'll need to process
batches of 3 items toward the end of processing. **In some cases Paginator needs some help dealing with partial matches:**

**If you have unrealized lazy sequence as input**

and/or

**If you have async run-fn** 

If you don't have these factors, you don't need to concern yourself with this topic. The issue is this: 

Let's say your batcher has 33/100 items. Do you submit this partial batch for execution, or do you try
to wait for ongoing futures or lazy input to resolve, to make the batch bigger? Waiting for too long to issue a partial
batch will increase the total time taken to page everything but at the same time, issuing more batches
will burn more of your API call limit or other such mechanism.

There is not straight-forward answer to this, hence you can specify the strategy to batcher.

[See batcher strategy document](doc/batcher-strategy.md)

## Example of paging of sub-elements

There are two ways how you can achieve this.
Your `run-fn` can return maps instead of PagingStates, in that case it's converted to a PagingState and added to processing,
or you can use the laziness of return to stick together multiple paging invocations

### Using the laziness of output

```clojure
(let [accounts (mapcat p/unwrap (p/paginate! user-accounts {:batcher (p/batcher 5)} users))
      groups (mapcat p/unwrap (p/paginate! account-groups {:batcher (p/batcher 5)} accounts))]
  (p/paginate group-projects {:batcher (p/batcher 5)} groups))
```

In this case you end up with a lazy sequence that will try to read `groups` lazy sequence, that will try to read `accounts`
lazy sequence. 

The pro of this approach is that it's very clojurish, the con is that it can be harder to share batching or concurrency
limits between stages and the callstack is deeper.

### Using additional elements injection

```clojure
(defn loader-fn [paging-states]
  (case (:type (first paging-states))
    :user (map #(assoc % :type :account) (user-accounts paging-states))
    :account (map #(assoc % :type :group) (account-groups paging-states))
    :group (group-projects paging-states)))

(p/paginate loader-fn {:batcher (p/grouped-batcher :type 5)} (map (assoc % :type :user) users))
```
This uses a grouped batcher by type to make sure you get paging states of a particular type per each `loader-fn` invocation.

Pro is that everything happens in one paging process, so you can apply concurrency limits and such. Con is that
it looks messier.

## A more complex example

[Listing branches via GitLab GraphQL API](doc/branches-example.md)

# License

Licensed under the term of the MIT License, see LICENSE.
