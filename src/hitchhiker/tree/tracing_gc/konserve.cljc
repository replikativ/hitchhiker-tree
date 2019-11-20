(ns hitchhiker.tree.tracing-gc.konserve
  (:require [konserve.core :as k]
            [hitchhiker.tree.tracing-gc :as gc]
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
  (if (and (vector? address)
           (= 2 (count address))
           (= (type epoch) (type (first address))))
    (not (neg? (compare-stamps (first address) epoch)))
    true))

(defrecord KonserveGCScratch [store epoch]
  gc/IGCScratch
  (observe-addr! [_ addr]
    (k/assoc store addr :marked))

  (observed? [_ addr]
    (async/go
      (or (after-epoch? addr epoch)
          (= :marked (async/<! (k/get store addr)))))))