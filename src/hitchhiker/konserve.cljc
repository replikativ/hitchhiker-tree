(ns hitchhiker.konserve
  (:refer-clojure :exclude [resolve subvec])
  (:require [clojure.core.rrb-vector :refer [catvec subvec]]
            #?(:clj [clojure.core.async :refer [chan promise-chan put!] :as async]
               :cljs [cljs.core.async :refer [chan promise-chan put!] :as async])
            [konserve.cache :as k]
            #?(:clj [clojure.core.cache :as cache]
               :cljs [cljs.cache :as cache])
            [konserve.memory :refer [new-mem-store]]
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [hitchhiker.tree.core :refer [go-try <? <??] :as core]
               :cljs [hitchhiker.tree.core :as core])
            [hitchhiker.tree.messaging :as msg]
            #?(:clj [hitchhiker.tree.async :refer [case-async]]))
  #?(:cljs (:require-macros [hitchhiker.tree.core :refer [go-try <?]]
                           [hitchhiker.tree.async :refer [case-async]])))


(defn synthesize-storage-addr
  "Given a key, returns a promise containing that key for use as a storage-addr"
  [key]
  (doto (promise-chan)
    (put! key)))

(defrecord KonserveAddr [store last-key konserve-key storage-addr]
  core/IResolve
  (dirty? [_] false)
  (dirty! [this] this)
  (last-key [_] last-key)
  (resolve [_]
    ;; inline konserve cache resolution
    (let [cache (:cache store)]
      (if-let [v (cache/lookup @cache konserve-key)]
        (go-try
          (swap! cache cache/hit konserve-key)
          (assoc v :storage-addr (synthesize-storage-addr konserve-key)))
        (go-try
            (let [ch (k/get-in store [konserve-key])]
              (-> (case-async
                    :none (async/<!! ch)
                    :core.async (<? ch))
                  (assoc :storage-addr (synthesize-storage-addr konserve-key)))))))))

(defn node->value [node]
  (-> (if (core/index-node? node)
        (-> (assoc node :storage-addr nil)
            (update :children (fn [cs] (mapv #(assoc %
                                                     :store nil
                                                     :storage-addr nil) cs))))
        (assoc node :storage-addr nil))
      (assoc :*last-key-cache nil)))

(defrecord KonserveBackend [store]
  core/IBackend
  (new-session [_] (atom {:writes 0
                          :deletes 0}))
  (anchor-root [_ {:keys [konserve-key] :as node}]
    node)
  (write-node [_ node session]
    (go-try
      (swap! session update-in [:writes] inc)
      (let [pnode (node->value node)]
        (let [id (uuid pnode)
              ch (k/assoc-in store [id] node)]
          (case-async
            :none (async/<!! ch)
            :core.async (<? ch))
          (->KonserveAddr store (core/last-key node) id (synthesize-storage-addr id))))))
  (delete-addr [_ addr session]
    (swap! session update :deletes inc)))

(defn get-root-key
  [tree]
  ;; TODO find out why this is inconsistent
  (or
    (-> tree :storage-addr (async/poll!) :konserve-key)
    (-> tree :storage-addr (async/poll!))))

(defn create-tree-from-root-key
  [store root-key]
  (go-try
   (let [val (let [ch (k/get-in store [root-key])]
               (case-async
                 :none (async/<!! ch)
                 :core.async (<? ch)))
         last-key (core/last-key (assoc val :storage-addr (synthesize-storage-addr root-key)))] ; need last key to bootstrap
     (<? (core/resolve
          (->KonserveAddr store last-key root-key (synthesize-storage-addr root-key)))))))

(defn add-hitchhiker-tree-handlers [store]
  (swap! (:read-handlers store) merge
         {'hitchhiker.konserve.KonserveAddr
          #(-> % map->KonserveAddr
               (assoc :store store
                      :storage-addr (synthesize-storage-addr (:konserve-key %))))
          'hitchhiker.tree.core.DataNode
          (fn [{:keys [children cfg]}]
            (core/data-node cfg
                            (into (sorted-map-by
                                   compare)
                                  children)))
          'hitchhiker.tree.core.IndexNode
          (fn [{:keys [children cfg op-buf]}]
            (core/index-node (vec children)
                             (promise-chan)
                             (vec op-buf)
                             cfg))
          'hitchhiker.tree.messaging.InsertOp
          msg/map->InsertOp
          'hitchhiker.tree.messaging.DeleteOp
          msg/map->DeleteOp
          'hitchhiker.tree.core.Config
          core/map->Config})
  (swap! (:write-handlers store) merge
         {'hitchhiker.konserve.KonserveAddr
          (fn [addr]
            (assoc addr
                   :store nil
                   :storage-addr nil))
          'hitchhiker.tree.core.DataNode
          (fn [node]
            (assoc node :storage-addr nil :*last-key-cache nil))
          'hitchhiker.tree.core.IndexNode
          (fn [node]
            (-> node
               (assoc :storage-addr nil :*last-key-cache nil)
               (update-in [:children]
                          (fn [cs] (map #(assoc %
                                                :store nil
                                                :*last-key-cache nil
                                                :storage-addr nil) cs)))))})
  store)
