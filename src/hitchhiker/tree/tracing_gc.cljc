(ns hitchhiker.tree.tracing-gc
  (:require [clojure.core.async :as async]
            [hitchhiker.tree :as hh]
            [hitchhiker.tree.node :as n]
            [hitchhiker.tree.utils.async :as ha]))

(defn mark
  "Mark addresses in gc-roots, traversing the entire tree for each root.
  If addresses is given, it must be a set containing possibly already-marked
  addresses.

  Returns all marked addresses."
  ([gc-roots] (mark gc-roots #{}))
  ([gc-roots addresses]
   (loop [addresses (volatile! (transient addresses))
          roots gc-roots]
     (if-let [root (first roots)]
       (do
         (loop [nodes [root]]
           (when-let [node (first nodes)]
             (let [node (if (hh/resolved? node)
                          node
                          (ha/<?? (n/-resolve-chan node)))
                   new-nodes (if (hh/index-node? node)
                               (into (subvec nodes 1) (:children node))
                               (subvec nodes 1))]
               (when-let [address (n/-raw-address node)]
                 (vswap! addresses conj! address))
               (recur new-nodes))))
         (recur addresses (rest roots)))
       (persistent! @addresses)))))

(defn sweep!
  "Walk all storage addresses from seq all-addresses, calling delete-fn on
  each key that for which accept-fn returns true, AND is not contained
  in the set addresses."
  [addresses all-addresses delete-fn]
  (loop [addrs all-addresses]
    (when-let [address (first addrs)]
      (when (not (contains? addresses address))
        (delete-fn address))
      (recur (rest addrs)))))