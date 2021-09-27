(ns org.clojars.roklenarcic.paginator.protocols)

(defprotocol BatchParser
  "Protocol that defines a result parser for batch result. Given a result of Runner execute, and paging state,
  it should be able to return paging cursor and items for that response."
  (-cursors [this batch-result]
    "Returns a fn that maps [entity-type id] keys to a cursor for each paging state from the response.
    e.g. a hashmap")
  (-items [this batch-result]
    "Returns a fn of [entity-type id] keys to a vector of items for each paging state from the response
    e.g. a hashmap.")
  (-new-entities [this batch-result]
    "Returns a vector of new page-states for new entities based on the Runner result.
    This is useful to paginate on subitems. Note that the present of :page-cursor key will
    be tested and it determined if these new entities go to result channel or if they get ran
    through the engine first."))
