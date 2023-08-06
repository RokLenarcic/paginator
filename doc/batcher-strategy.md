# Partial Batch strategy

Only applies when:

**If you have unrealized lazy sequence as input**

and/or

**If you have async run-fn**

The batcher will either issue a batch of wait for more input or futures finishing. For efficiency, it's recommended that 
your input to batcher is a vector, list or realized sequence.

Here's the tuning knobs for the mechanism of premature partial batches.

### Strategy map options

Strategy can be a map with these options:
#### :time-boost-start
After this many ms have elapsed since last batch, the batcher will start considering premature partial batch,
starting with batches that are slightly less than full, then ramping up as time goes by. Default 30ms.
#### :time-boost-end
After this many ms have elapsed since last batch, the requirement for issuing premature partial batch will be
at the lowest, as specified by `:min-items-ratio`. Default 100ms.
#### :min-items-ratio
The lowest bound for premature batch, default is `0.3`, requiring the batch to be at least 30% full to be
prematurely issued.

### Shorthand

Instead of map of settings you can just use nil or a short-hand for a preset:
- :min-batches sets `min-items-ratio` to `1`, disallowing premature batches 

### Concurrency

Batcher will never issue a partial batch is it thinks that `run-fn` has no more concurrency capacity: 

- if you are using `p/async-fn` helper, then the concurrency limit is automatically observed
- otherwise you can specify concurrency manually in options: `(p/paginate! [run-fn {:concurrency 5} input])`
- if no information is given then it's assumed `run-fn` has more capacity, and this mechanism won't stop partial batches
