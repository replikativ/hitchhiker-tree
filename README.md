# Hitchhiker Tree

<p align="center">
<a href="https://clojurians.slack.com/archives/CB7GJAN0L"><img src="https://img.shields.io/badge/clojurians%20slack-join%20channel-blueviolet"/></a>
<a href="https://clojars.org/io.replikativ/hitchhiker-tree"> <img src="https://img.shields.io/clojars/v/io.replikativ/hitchhiker-tree.svg" /></a>
<a href="https://circleci.com/gh/replikativ/hitchhiker-tree"><img src="https://circleci.com/gh/replikativ/hitchhiker-tree.svg?style=shield"/></a>
<a href="https://github.com/replikativ/hitchhiker-tree/tree/development"><img src="https://img.shields.io/github/last-commit/replikativ/hitchhiker-tree/development"/></a>
<a href="https://versions.deps.co/replikativ/hitchhiker-tree" title="Dependencies Status"><img src="https://versions.deps.co/replikativ/hitchhiker-tree/status.svg" /></a>
</p>

Hitchhiker trees are a datastructure [invented by David Greenberg](https://github.com/datacrypt-project/hitchhiker-tree), synthesizing fractal trees and functional data structures, to create fast, snapshottable, massively scalable databases.

We documented our extended design [here](https://blog.datopia.io/2018/11/03/hitchhiker-tree/). This repository currently reflects its development as a backend for [Datahike](https://github.com/replikativ/datahike). This respository adds ClojureScript support and provides a [core.async](https://github.com/clojure/core.async) backend for [konserve](https://github.com/replikativ/konserve) including a [Merkle](https://en.wikipedia.org/wiki/Merkle_tree) variant of the tree, effectively making the hitchhiker much more portable and apt for distributed infrastructure.

## What's in this Repository?

The hitchhiker namespaces contain a complete implementation of a persistent, serializable, lazily-loaded hitchhiker tree.
This is a sorted key-value datastructure, like a scalable `sorted-map`.
It can incrementally persist and automatically lazily load itself from any backing store which implements a simple protocol.

## Usage

Add this dependency to your project:

[![Clojars Project](http://clojars.org/io.replikativ/hitchhiker-tree/latest-version.svg)](http://clojars.org/io.replikativ/hitchhiker-tree)

## Current API

We use [tree.cljc](src/hitchhiker/tree.cljc) and [messaging.cljc](src/hitchhiker/tree/messaging.cljc) as the core API. The following snippet is extracted from the [konserve tests](test/hitchhiker/konserve_test.cljc).

```clojure
(ns hitchhiker-tree.sandbox
  (:require [hitchhiker.tree :as core]
            [hitchhiker.tree.messaging :as msg]
            [hitchhiker.tree.bootstrap.konserve :as kons]
            [hitchhiker.tree.utils.async :as ha]
            [konserve.cache :as kc]
            [konserve.filestore :refer [new-fs-store delete-store list-keys]]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest testing run-tests is]
             ]))
         
         
(let [folder "/tmp/async-hitchhiker-tree-test"
      _ (delete-store folder)
      store (kons/add-hitchhiker-tree-handlers
             (kc/ensure-cache (async/<!! (new-fs-store folder :config {:fsync false}))))
      backend (kons/->KonserveBackend store)
      config (core/->Config 1 3 (- 3 1))
      flushed (ha/<?? (core/flush-tree
                       (time (reduce (fn [t i]
                                       (ha/<?? (msg/insert t i i)))
                                     (ha/<?? (core/b-tree config))
                                     (range 1 11)))
                       backend))
      root-key (kons/get-root-key (:tree flushed))
      tree (ha/<?? (kons/create-tree-from-root-key store root-key))]
  (is (= (ha/<?? (msg/lookup tree -10)) nil))
  (is (= (ha/<?? (msg/lookup tree 100)) nil))
  (dotimes [i 10]
    (is (= (ha/<?? (msg/lookup tree (inc i))) (inc i))))
  (is (= (map first (msg/lookup-fwd-iter tree 4)) (range 4 11)))
  (is (= (map first (msg/lookup-fwd-iter tree 0)) (range 1 11)))
  (let [deleted (ha/<?? (core/flush-tree (ha/<?? (msg/delete tree 3)) backend))
        root-key (kons/get-root-key (:tree deleted))
        tree (ha/<?? (kons/create-tree-from-root-key store root-key))]
    (is (= (ha/<?? (msg/lookup tree 2)) 2))
    (is (= (ha/<?? (msg/lookup tree 3)) nil))
    (is (= (ha/<?? (msg/lookup tree 4)) 4)))
  (delete-store folder))
```


## Benchmarking

This library includes a detailed, instrumented benchmarking suite.
It's built to enable comparative benchmarks between different parameters or code changes, so that improvements to the structure can be correctly categorized as such, and bottlenecks can be reproduced and fixed.

To try it, just run

    lein bench

The benchmark tool supports testing with different parameters, such as:

- The tree's branching factor
- Whether to enable fractal tree features, just use the B-tree features, or compare to a vanilla Clojure sorted map
- Reordering of delete operations (to stress certain workloads)
- Whether to use the in-memory or Redis-backed implementation

The benchmarking tool is designed to make it convenient to run several benchmarks;
each benchmark's parameters can be separate by a `--`.
This makes it easy to understand the characteristics of the hitchhiker tree over a variety of settings for a parameter.

You can run a more sophisticated experiment benchmark by doing

    lein bench OUTPUT_DIR options -- options-for-2nd-experiment -- options-for-3rd-experiment

This generates an Excel workbooks called "analysis.xlsx" with benchmark results.
For instance, if you'd like to run experiments to understand the performance difference between various values of B (the branching factor), you can do:

    lein bench perf_diff_experiment -b 10 -- -b 20 -- -b 40 -- -b 80 -- -b 160 -- -b 320 -- -b 640

And it will generate lots of data and the Excel workbook for analysis.

If you'd like to see the options for the benchmarking tool, just run `lein bench`.



## Original Outboard Redis API

_Note that this API is not actively developed, but can still be useful if you are interested in Redis._


Outboard is a simple API for your Clojure applications that enables you to make use of tens of gigabytes of local memory, far beyond what the JVM can manage.
Outboard also allows you to restart your application and reuse all of that in-memory data, which dramatic reduces startup times due to data loading.

Outboard has a simple API, which may be familiar if you've ever used Datomic.
Unlike Datomic, however, Outboard trees can be "forked" like git repositories, not just transacted upon.
Once you've created a tree, you can open a connection to it.
The connection mediates all interactions with the outboard data:
it can accept transactions, provide snapshots for querying, and be cloned.

### API Usage Example

```clojure
(require '[hitchhiker.tree.bootstrap.outboard :as ob])

;; First, we'll create a connection to a new outboard
(def my-outboard (ob/create "first-outboard-tree"))

;; We'll get a snapshot of the outboard's current state, which is empty for now
;; Note that snapshots are only valid for 5 seconds, but making a new snapshot is free
;; It would be easy to write an "extend-life" function for snapshots
(def first-snapshot (ob/snapshot my-outboard))

;; This will insert the pair "hello" "world" only into the snapshot
(-> first-snapshot
    (ob/insert "hello" "world")
    (ob/lookup "hello"))
;;=> "world"

;; Inserts must be done in a transaction to persist
(-> (ob/snapshot my-outboard)
    (ob/lookup "hello"))
;;=> nil

;; We can insert some data into it via a transaction
;; The update! function is atomic, just like swap! for atoms
;; update! will pass its transaction function a snapshot of the outboard
(ob/update! my-outboard (fn [snapshot] (ob/insert snapshot "goodbye" "moon")))

;; Since the insert was transacted, it persists
(-> (ob/snapshot my-outboard)
    (ob/lookup "goodbye"))
;;=> "moon"

;; If you'd like, you can "fork" an outboard. Let's fork our outboard.
;; To fork, you just save a snapshot under a new name
(def forked-outboard (ob/save-as (ob/snapshot my-outboard) "forked-outboard"))

;; Now, we can transact into the snapshot, which will not affect other forks
(ob/update! forked-outboard (fn [snapshot] (ob/insert snapshot "goodbye" "sun")))

;; As we can see:
(-> (ob/snapshot my-outboard)
    (ob/lookup "goodbye"))
;;=> "moon"
(-> (ob/snapshot forked-outboard)
    (ob/lookup "goodbye"))
;;=> "sun"
```

You should check out the docstrings/usage of these functions, too:

- `close` will gracefully shut down an outboard connection
- `open` will reopen an outboard (you can only create outboards which don't exist)
- `destroy` will delete all data related to the closed, named outboard
- `lookup` and `lookup-fwd-iter` provide single and ordered sequence access to snapshots

## Background

Outboard is an off-heap functionally persistent sorted map.
This map allows your applications to retain huge data structures in memory across process restarts.

Outboard is the the first library to make use of hitchhiker trees.
Hitchhiker trees are a functionally persistent, serializable, off-heap fractal B tree.
They can be extended to contain a mechanism to make statistical analytics blazingly fast, and to support column-store facilities.

Details about hitchhiker trees, including related work, can be found in `docs/hitchhiker.adoc`.

## Testing

You'l need a local Redis instance running to run the tests. Once you have it, just run

    lein test

## Technical details

See the `doc/` folder for technical details of the hitchhiker tree and Redis garbage collection system.

## Gratitude

Thanks to the early reviewers, Kovas Boguta & Leif Walsh.
Also, thanks to Tom Faulhaber for making the Excel analysis awesome!

## License

Copyright © 2016 David Greenberg, 2017-2020 Christian Weilbach 

Distributed under the Eclipse Public License version 1.0
