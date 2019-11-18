(ns hitchhiker.tree.tracing-gc.konserve
  (:require [clojure.tools.logging :as log]
            [hitchhiker.tree.tracing-gc :as gc]
            [konserve.core :as k]
            #?(:clj  [clojure.core.async :as async]
               :cljs [cljs.core.async :as async :include-macros true])))

(defn- within-epoch?
  [address epoch]
  (if-let [addr-ts (some-> (when (string? address)
                             (re-matches #"([0-9a-f]{16})\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" address))
                           (second))]
    (not (pos? (compare addr-ts epoch)))
    true))

(defrecord KonserveGCScratch [store epoch]
  gc/IGCScratch
  (observe-addr! [_ addr]
    (log/debug :task ::gc/observe-addr! :addr addr)
    (k/assoc store addr :marked))

  (observed? [_ addr]
    (async/go
      (log/debug :task ::gc/observed? :phase :begin :addr addr)
      (let [result (or (within-epoch? addr epoch)
                       (= :marked (async/<! (k/get store addr))))]
        (log/debug :task ::gc/observed? :phase :end :addr addr :result result)
        result))))
