(ns hitchhiker.tree.tracing-gc.epoch
  "A GC scratch implementation that considers timestamped
  keys, and only considers keys that are marked before some
  epoch.

  Only recognizes keys that are a vector, and which the first
  element is comparable to the epoch."
  (:require [hitchhiker.tree.tracing-gc :as gc]
            #?(:clj  [clojure.core.async :as async]
               :cljs [cljs.core.async :as async :include-macros true])))

(defn compare-stamps
  [e1 e2]
  #?(:clj (compare e1 e2)
     :cljs (if (and (= js/Date (type e1)) (= js/Date (type e2)))
             (compare (.getTime e1) (.getTime e2))
             (compare e1 e2))))

(defn- after-epoch?
  [address epoch]
  (if (and (sequential? address)
           (<= 1 (count address))
           (= (type epoch) (type (first address))))
    (not (neg? (compare-stamps (first address) epoch)))
    true))

(defrecord EpochBasedGCScratch [store epoch]
  gc/IGCScratch
  (observe-addr! [_ addr]
    (swap! store conj addr))

  (observed? [_ addr]
    (or (after-epoch? addr epoch)
        (contains? @store addr))))