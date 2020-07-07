(ns hitchhiker.tree.utils.gc-test
  (:refer-clojure :exclude [compare resolve satisfies?])
  (:require
   [clojure.test :refer :all]
   [hitchhiker.tree :as tree]
   [hitchhiker.tree.messaging :as msg]
   [hitchhiker.tree.backend.testing :refer :all]
   [hitchhiker.tree.node.testing :refer :all]
   [hitchhiker.tree.utils.async :refer :all :as ha]
   [hitchhiker.tree.utils.gc :refer :all :as gc]
   [hitchhiker.tree.bootstrap.konserve :as kons]
   [konserve.core :as k]
   [konserve.cache :as kc]
   [konserve.gc :refer [sweep!]]
   [konserve.filestore :refer [new-fs-store delete-store]]
   [clojure.core.async :refer [<!! <! go chan put! close!] :as async]))

(deftest hitchhiker-tree-gc-test
  (testing "Test the GC."
    #_(let [folder "/tmp/hh-tree-gc"
          _      (delete-store folder)
          store (kons/add-hitchhiker-tree-handlers
                 (kc/ensure-cache (async/<!! (new-fs-store folder :config {:fsync false}))))
          backend (kons/->KonserveBackend store)
          flushed (ha/<?? (tree/flush-tree
                           (time (reduce (fn [t i]
                                           (ha/<?? (msg/insert t i i)))
                                         (ha/<?? (tree/b-tree (core/->Config 1 3 (- 3 1))))
                                         (range 1 11)))
                           backend))
          flushed-second (ha/<?? (core/flush-tree
                                  (time (reduce (fn [t i]
                                                  (ha/<?? (msg/insert t i i)))
                                                (ha/<?? (core/b-tree (core/->Config 1 3 (- 3 1))))
                                                (range 12 21)))
                                  backend))
          root-key (kons/get-root-key (:tree flushed))]
      (let [ts        (java.util.Date.)
            whitelist (gc/mark #{root-key})]

        #_(is (= #{:foo2 :foo3} (<!! (sweep! store whitelist ts))))))))


(comment
  ;; WIP, will become test as soon as konserve key iteration works properly
  (let [folder           "/tmp/hh-tree-gc"
        _                (delete-store folder)
        store            (kons/add-hitchhiker-tree-handlers
                          (kc/ensure-cache (async/<!! (new-fs-store folder))))
        backend          (kons/->KonserveBackend store)
        flushed          (ha/<?? (tree/flush-tree
                                  (time (reduce (fn [t i]
                                                  (ha/<?? (msg/insert t i i)))
                                                (ha/<?? (tree/b-tree (tree/->Config 2 3 (- 3 2))))
                                                (range 1 11)))
                                  backend))
        root-key         (kons/get-root-key (:tree flushed))
        root-node        (ha/<?? (kons/create-tree-from-root-key store root-key))
        flushed-second   (ha/<?? (tree/flush-tree
                                  (time (reduce (fn [t i]
                                                  (ha/<?? (msg/insert t i i)))
                                                (:tree flushed)
                                                (range 12 21)))
                                  backend))
        root-key-second  (kons/get-root-key (:tree flushed-second))
        root-node-second (ha/<?? (kons/create-tree-from-root-key store root-key-second))

        ]
    (let [ts               (java.util.Date.)
          whitelist        (gc/mark #{root-node})
          whitelist-second (gc/mark #{root-node-second})]
      #_(count whitelist-second)
      #_(map first (msg/lookup-fwd-iter root-node 0))
      #_(async/<!! (sweep! store whitelist ts))
      #_whitelist
      (async/<!! (k/keys store))
      #_(is (= #{:foo2 :foo3} (<!! (sweep! store whitelist ts)))))))
