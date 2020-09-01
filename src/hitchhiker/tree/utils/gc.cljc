(ns hitchhiker.tree.utils.gc
  (:require [hitchhiker.tree :as hh]
            [hitchhiker.tree.node :as n]
            [hitchhiker.tree.bootstrap.konserve :as kons]
            [hitchhiker.tree.utils.async :as ha]))

(defn mark
  "Return a set of all addresses reachable from gc-roots, corresponding to the
  mark phase of a garbage collector."
  ([gc-roots]
   (ha/go-try
    (loop [to-visit gc-roots
           visited  #{}]
      (if-let [to-visit (seq to-visit)]
        (let [[node & r] to-visit
              node       (if (hh/resolved? node)
                           node
                           (ha/<? (n/-resolve-chan node)))
              new-nodes  (when (hh/index-node? node)
                           (:children node))]
          (recur (into r new-nodes)
                 (conj visited (kons/get-root-key node))))
        visited)))))
