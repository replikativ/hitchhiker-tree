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

(defn- before-epoch?
  [address epoch]
  (if (and (sequential? address)
           (<= 1 (count address))
           (= (type epoch) (type (first address))))
    (neg? (compare-stamps (first address) epoch))
    false))

(defn accept-before-epoch
  "Return a function that accepts addresses that occur
  before the given epoch. Addresses that are not a sequential
  value and don't have a compatible timestamp as the first
  element are not accepted."
  [epoch]
  (fn [address] (before-epoch? address epoch)))