(ns hitchhiker.tree.utils.gc-test
  (:refer-clojure :exclude [compare resolve satisfies?])
  (:require
   [clojure.test :refer :all]
   [hitchhiker.tree :as tree]
   [hitchhiker.tree.messaging :as msg]
   [hitchhiker.tree.utils.async  :as ha]
   [hitchhiker.tree.utils.gc :as gc]
   [hitchhiker.tree.bootstrap.konserve :as kons]
   [clojure.data :refer [diff]]
   [konserve.core :as k]
   [konserve.cache :as kc]
   [konserve.gc :refer [sweep!]]
   [konserve.filestore :refer [new-fs-store delete-store]]
   [clojure.core.async :refer [<!! <! go chan put! close!] :as async]))

#_(deftest hitchhiker-tree-gc-test
    (testing "Test the GC."
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
                                                    (range 11 21)))
                                      backend))
            root-key-second  (kons/get-root-key (:tree flushed-second))
            root-node-second (ha/<?? (kons/create-tree-from-root-key store root-key-second))

            ts               (java.util.Date.)
            whitelist        (async/<!! (gc/mark #{root-node}))
            whitelist-second (async/<!! (gc/mark #{root-node-second}))
            removed          (async/<!! (sweep! store whitelist-second ts))]
        (is (= removed (first (diff whitelist whitelist-second))))
        (is (= (set (map :key (async/<!! (k/keys store))))
               whitelist-second))
        (is (= (map first (msg/lookup-fwd-iter root-node-second 0))
               (range 1 21)))
      ;; check that with empty caches we can still load the data
        (let [reloaded-store     (kons/add-hitchhiker-tree-handlers
                                  (kc/ensure-cache (async/<!! (new-fs-store folder))))
              root-node-after-gc (ha/<?? (kons/create-tree-from-root-key reloaded-store root-key-second))]
          (is (= (map first (msg/lookup-fwd-iter root-node-after-gc 0))
                 (range 1 21)))))))



