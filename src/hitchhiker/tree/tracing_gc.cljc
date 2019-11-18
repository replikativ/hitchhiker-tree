(ns hitchhiker.tree.tracing-gc
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [hitchhiker.tree :as hh]
            [hitchhiker.tree.node :as n]
            [hitchhiker.tree.utils.async :as ha]))

(defprotocol IGCScratch
  (observe-addr! [this addr] "Marks the given addr as being currently active")
  (observed? [this addr] "Returns true if the given addr was observed"))

(defmacro do-<!
  "Force into an async taking call.

  Evaluates to <! when in an async backend.
  Wraps form in a thread when non-async."
  [& form]
  (ha/if-async?
    `(async/<! ~@form)
    `(async/<! (async/thread ~@form))))

(defn trace-gc!
  "Does a tracing GC and frees up all unused keys.
   This is a simple mark-sweep algorithm.

   gc-scratch should be an instance of IGCScratch
   gc-roots should be a list of the roots of currently active trees.
   all-keys should be a core.async channel that will contain every key in storage.
   delete-fn will be called on every key that should be deleted during the sweep phase. It is expected to return a channel that yields when the item is deleted."
  [gc-scratch gc-roots all-keys delete-fn]
  (let [mark-phase (async/go-loop [roots gc-roots]
                     (when-let [root (first roots)]
                       (loop [nodes [root]]
                         (when-let [node (first nodes)]
                           (log/debug :task ::trace-gc! :phase :marking :visiting-node (async/poll! (:storage-addr node)))
                           (let [node (if (hh/resolved? node)
                                        node
                                        (do-<! (do
                                                 (log/debug :task ::trace-gc! :phase :marking :resolve-node node)
                                                 (n/-resolve-chan node))))
                                 nodes (if (hh/index-node? node)
                                         (into (subvec nodes 1) (:children node))
                                         (subvec nodes 1))]
                             (when-let [address (async/poll! (:storage-addr node))]
                               (async/<! (observe-addr! gc-scratch address)))
                             (recur nodes))))
                       (recur (rest roots))))]
    (async/go
      (async/<! mark-phase)
      (loop []
        (when-let [address (async/<! all-keys)]
          (when-not (async/<! (observed? gc-scratch address))
            (async/<! (delete-fn address)))
          (recur))))))