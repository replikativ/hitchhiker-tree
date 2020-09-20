(ns hitchhiker.konserve-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             #?(:clj :refer :cljs :refer-macros) [deftest testing run-tests is
                                                  #?(:cljs async)]]
            [clojure.test.check.clojure-test #?(:clj :refer :cljs :refer-macros) [defspec]]
            [clojure.test.check.generators :as gen :include-macros true]
            [clojure.test.check.properties :as prop :include-macros true]
            [konserve.filestore :refer [new-fs-store delete-store]]
            [konserve.memory :refer [new-mem-store]]
            [konserve.core :as k]
            [hitchhiker.tree.bootstrap.konserve :as kons]
            [konserve.cache :as kc]
            [konserve.core :as k]
            [hasch.core :as hasch]
            [hitchhiker.tree :as core]
            [hitchhiker.tree.utils.async :as ha :include-macros true]
            [hitchhiker.tree.messaging :as msg]
            [hitchhiker.ops :refer [recorded-ops]]
            #?(:cljs [cljs.core.async :refer [promise-chan] :as async]
               :clj [clojure.core.async :refer [promise-chan] :as async])
            #?(:cljs [cljs.nodejs :as nodejs])
            [clojure.string :as str]))

#?(:cljs
   (do
     (nodejs/enable-util-print!)
     (enable-console-print!)))

(defn iter-helper [tree key]
  (ha/go-try
   (let [iter-ch (async/chan)
         path (ha/<? (core/lookup-path tree key))]
     (ha/if-async?
      (do
        (when path
          (msg/forward-iterator iter-ch path key))
        (ha/<? (async/into [] iter-ch)))
      (msg/forward-iterator path key)))))


(deftest simple-konserve-test
  (testing "Insert and lookup"
    #?(:cljs
       (async done
              (ha/go-try
               (let [folder "/tmp/async-hitchhiker-tree-test"
                     _ (delete-store folder)
                     store (kons/add-hitchhiker-tree-handlers
                            (kc/ensure-cache (async/<!
                                              (new-mem-store)
                                              #_(new-fs-store folder)))) ;; always use core.async here!
                     backend (kons/->KonserveBackend store)
                     init-tree (ha/<? (ha/reduce< (fn [t i] (msg/insert t i i))
                                                  (ha/<? (core/b-tree (core/->Config 1 3 (- 3 1))))
                                                  (range 1 11)))
                     flushed (ha/<? (core/flush-tree init-tree backend))
                     root-key (kons/get-root-key (:tree flushed))
                     tree (ha/<? (kons/create-tree-from-root-key store root-key))]
                 (is (= (ha/<? (msg/lookup tree -10)) nil))
                 (is (= (ha/<? (msg/lookup tree 100)) nil))
                 (dotimes [i 10]
                   (is (= (ha/<? (msg/lookup tree (inc i))) (inc i))))
                 (is (= (map first (ha/<? (iter-helper tree 4))) (range 4 11)))
                 (is (= (map first (ha/<? (iter-helper tree 0))) (range 1 11)))
                 (let [deleted (ha/<? (core/flush-tree (ha/<? (msg/delete tree 3)) backend))
                       root-key (kons/get-root-key (:tree deleted))
                       tree (ha/<? (kons/create-tree-from-root-key store root-key))]
                   (is (= (ha/<? (msg/lookup tree 2)) 2))
                   (is (= (ha/<? (msg/lookup tree 3)) nil))
                   (is (= (ha/<? (msg/lookup tree 4)) 4)))
                 (delete-store folder)
                 (done)))))
    #?(:clj
       (let [folder "/tmp/async-hitchhiker-tree-test"
             _ (delete-store folder)
             store (kons/add-hitchhiker-tree-handlers
                    (kc/ensure-cache (async/<!! (new-fs-store folder :config {:fsync false}))))
             backend (kons/->KonserveBackend store)
             flushed (ha/<?? (core/flush-tree
                              (time (reduce (fn [t i]
                                              (ha/<?? (msg/insert t i i)))
                                            (ha/<?? (core/b-tree (core/->Config 1 3 (- 3 1))))
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
         (delete-store folder)))))



(let [folder   "/tmp/async-hitchhiker-tree-test"
      _        (delete-store folder)
      store    (kons/add-hitchhiker-tree-handlers
             (kc/ensure-cache (async/<!! (new-mem-store))))
      backend  (kons/->KonserveBackend store)
      flushed  (ha/<?? (core/flush-tree
                       (time (reduce (fn [t i]
                                       (ha/<?? (msg/insert t i i)))
                                     (ha/<?? (core/b-tree (core/->Config 1 3 (- 3 1))))
                                     (range 1 11)))
                       backend))
      root-key (kons/get-root-key (:tree flushed))
      tree     (ha/<?? (kons/create-tree-from-root-key store root-key))]
  (ha/<?? (msg/lookup tree -10)))


;; ;; adapted from redis tests

(defn insert
  [t k]
  (msg/insert t k k))

(defn ops-test [ops universe-size]
  (ha/go-try
   (let [folder (str "/tmp/konserve-mixed-workload" (hasch/uuid))
         _ #?(:clj (delete-store folder) :cljs nil)
         store (kons/add-hitchhiker-tree-handlers
                (kc/ensure-cache
                 #?(:clj (async/<!! (new-fs-store folder :config {:fsync false}))
                    :cljs (async/<! (new-mem-store)))))
         _ #?(:clj (assert (empty? (async/<!! (k/keys store)))
                           "Start with no keys")
              :cljs nil)
                                        ;_ (swap! recorded-ops conj ops)
         [b-tree root set]
         (ha/<? (ha/reduce< (fn [[t root set] [op x]]
                              (ha/go-try
                               (let [x-reduced (when x (mod x universe-size))]
                                 (case op
                                   :flush (let [flushed (ha/<? (core/flush-tree t (kons/->KonserveBackend store)))
                                                t (:tree flushed)]
                                            [t (ha/<? (:storage-addr t)) set])
                                   :add [(ha/<? (insert t x-reduced)) root (conj set x-reduced)]
                                   :del [(ha/<? (msg/delete t x-reduced)) root (disj set x-reduced)]))))
                            [(ha/<? (core/b-tree (core/->Config 3 3 2))) nil #{}]
                            ops))]
     (let [b-tree-order (seq (map first (ha/<? (iter-helper b-tree -1))))
           res (= b-tree-order (seq (sort set)))]
       (assert res (str "These are unequal: " (pr-str b-tree-order) " " (pr-str (seq (sort set)))))
       res))))

;; TODO recheck when https://dev.clojure.org/jira/browse/TCHECK-128 is fixed
;; and share clj mixed-op-seq test, remove ops.cljc then.
#?(:cljs
   (deftest manual-mixed-op-seq
     (async done
            (ha/go-try
             (loop [[ops & r] recorded-ops]
               (when ops
                 (is (ha/<? (ops-test ops 1000)))
                 (recur r)))
             (done)))))


#?(:clj
   (defn mixed-op-seq
     "This is like the basic mixed-op-seq tests, but it also mixes in flushes to a konserve filestore"
     [add-freq del-freq flush-freq universe-size num-ops]
     (prop/for-all [ops (gen/vector (gen/frequency
                                     [[add-freq (gen/tuple (gen/return :add)
                                                           (gen/no-shrink gen/int))]
                                      [flush-freq (gen/return [:flush])]
                                      [del-freq (gen/tuple (gen/return :del)
                                                           (gen/no-shrink gen/int))]])
                                    num-ops)]
                   (ha/<?? (ops-test ops universe-size)))))


;; #?(:clj
;;    (defspec test-many-keys-bigger-trees
;;      100
;;      (mixed-op-seq 800 200 10 1000 1000)))


#?(:cljs
   (defn ^:export test-all [cb]
     (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
       (cb (clj->js m))
       (if (cljs.test/successful? m)
         (println "Success!")
         (println "FAIL")))
     (run-tests)))
