(ns hitchhiker.tree.backend.testing
  (:require
   [#?(:clj hitchhiker.tree.utils.clojure.async
       :cljs hitchhiker.tree.utils.cljs.async) :as ha]
   [hitchhiker.tree.node :as n]
   [hitchhiker.tree.node.testing :as tn]
   [hitchhiker.tree :as tree]
   [hitchhiker.tree.backend :as b]))

(defrecord TestingBackend []
  b/IBackend
  (-new-session [_] (atom {:writes 0}))
  (-anchor-root [_ root] root)
  (-write-node [_ node session]
    (swap! session update-in [:writes] inc)
    (ha/go-try
     (tn/testing-addr (n/-last-key node)
                      (assoc node :*last-key-cache (tree/cache)))))
  (-delete-addr [_ addr session]))
