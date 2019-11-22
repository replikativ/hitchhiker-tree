(ns hitchhiker.tree.tracing-gc-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hitchhiker.tree :as tree]
            [hitchhiker.tree.messaging :as hmsg]
            [hitchhiker.tree.bootstrap.konserve :as kons]
            [konserve.cache :as kc]
            [konserve.core :as k]
            [konserve.memory :as mem]
            [hitchhiker.tree.utils.async :as ha]
            [hitchhiker.tree.tracing-gc :as gc]
            [hitchhiker.tree.tracing-gc.epoch :as gce])
  (:import [java.util Date]))

(deftest test-tracing-gc
  (let [store (kc/ensure-cache (async/<!! (mem/new-mem-store)))
        tree (volatile! (tree/b-tree (tree/->Config 2 4 2)))]
    (dotimes [i 32]
      (vswap! tree hmsg/insert i i))
    (ha/<?? (tree/flush-tree @tree (kons/->KonserveBackend store nil)))
    (testing "that GC doesn't remove anything if there are no garbage nodes."
      (let [all-storage-keys (async/<!! (async/into [] (k/keys store)))
            removed (volatile! #{})
            scratch (gce/->EpochBasedGCScratch (atom #{}) (Date.))
            delete-fn (fn [k]
                        (async/go
                          (vswap! removed conj k)
                          (async/<! (k/dissoc store k))))]
        (gc/trace-gc! scratch [@tree] (k/keys store) delete-fn)
        (is (empty? @removed))
        (is (= (set all-storage-keys) (async/<!! (async/into #{} (k/keys store)))))))
    (testing "that GC removed garbage nodes"
      (dotimes [i 32]
        (vswap! tree hmsg/insert (+ i 32) (+ i 32)))
      (dotimes [i 32]
        (vswap! tree hmsg/delete i))
      (ha/<?? (tree/flush-tree @tree (kons/->KonserveBackend store nil)))
      (let [live-nodes (loop [nodes [@tree]
                              live #{}]
                         (if-let [node (first nodes)]
                           (let [node (if (tree/resolved? node) node (tree/<?-resolve node))
                                 live (if-let [addr (async/poll! (:storage-addr node))]
                                        (conj live (:konserve-key addr))
                                        live)]
                             (if (tree/index-node? node)
                               (recur (into (subvec nodes 1) (:children node)) live)
                               (recur (subvec nodes 1) live)))
                           live))
            removed (volatile! #{})
            scratch (gce/->EpochBasedGCScratch (atom #{}) (Date.))
            delete-fn (fn [k]
                        (vswap! removed conj k)
                        (async/<!! (k/dissoc store k)))]
        (gc/trace-gc! scratch [@tree] (k/keys store) delete-fn)
        (is (seq @removed))
        (is (= live-nodes (async/<!! (async/into #{} (k/keys store))))))
      (testing "that GC leaves a usable tree in storage"
        (let [tree2 (ha/<?? (kons/create-tree-from-root-key store (kons/get-root-key @tree)))
              iter (hmsg/lookup-fwd-iter tree2 0)]
          (dotimes [i 32]
            (is (= (+ i 32) (ha/<?? (hmsg/lookup tree2 (+ i 32))))))
          (is (= (map #(vector % %) (range 32 64)) (seq iter))))))))