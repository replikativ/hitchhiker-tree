(ns hitchhiker.tree.codec.nippy
  (:require
   [hitchhiker.tree :as tree]
   #?(:clj [taoensso.nippy :as nippy])))

(defonce install*
  (delay
   #?@(:clj [(nippy/extend-freeze hitchhiker.tree.IndexNode :b-tree/index-node
                                  [{:keys [storage-addr cfg children op-buf]} data-output]
                                  (nippy/freeze-to-out! data-output cfg)
                                  (nippy/freeze-to-out! data-output children)
                                  (nippy/freeze-to-out! data-output (into [] op-buf)))

             (nippy/extend-thaw :b-tree/index-node
                                [data-input]
                                (let [cfg (nippy/thaw-from-in! data-input)
                                      children (nippy/thaw-from-in! data-input)
                                      op-buf (nippy/thaw-from-in! data-input)]
                                  (tree/index-node children op-buf cfg)))

             (nippy/extend-freeze hitchhiker.tree.DataNode :b-tree/data-node
                                  [{:keys [cfg children]} data-output]
                                  (nippy/freeze-to-out! data-output cfg)
                                  (nippy/freeze-to-out! data-output children))

             (nippy/extend-thaw :b-tree/data-node
                                [data-input]
                                (let [cfg (nippy/thaw-from-in! data-input)
                                      children (nippy/thaw-from-in! data-input)]
                                  (tree/data-node children cfg)))])))

(defn ensure-installed!
  []
  @install*)
