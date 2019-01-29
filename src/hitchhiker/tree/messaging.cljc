(ns hitchhiker.tree.messaging
  (:refer-clojure :exclude [subvec])
  (:require [hitchhiker.tree.async]
            [clojure.core.rrb-vector :refer [catvec]]
            [hasch.core :refer [uuid]]
            #?(:clj [clojure.core.async :as async]
               :cljs [cljs.core.async :as async])
            #?(:clj [clojure.pprint :as pp])
            #?(:clj [hitchhiker.tree.core :refer [go-try <? <??] :as core]
              :cljs [hitchhiker.tree.core :as core])
            #?(:clj [hitchhiker.tree.async :refer [case-async]]))
  #?(:clj (:import java.io.Writer))
  #?(:cljs (:require-macros [hitchhiker.tree.core :refer [go-try <? <?resolve]]
                           [hitchhiker.tree.async :refer [case-async]])))

;; An operation is an object with a few functions
;; 1. It has a function that it applies to the tree to apply its effect
;; In the future, it could also have
;; 2. It has a promise which can be filled with the end result
;;       (more memory but faster results for repeat queries)

(defprotocol IOperation
  (affects-key [op] "Which key this affects--currently must be a single key")
  (apply-op-to-coll [op coll] "Applies the operation to the collection")
  (apply-op-to-tree [op tree] "Applies the operation to the tree. Returns go-block."))

(defrecord InsertOp [key value]
  IOperation
  (affects-key [_] key)
  (apply-op-to-coll [_ map] (assoc map key value))
  (apply-op-to-tree [_ tree] (core/insert tree key value)))

(defrecord DeleteOp [key]
  IOperation
  (affects-key [_] key)
  (apply-op-to-coll [_ map] (dissoc map key))
  (apply-op-to-tree [_ tree] (core/delete tree key)))

#?(:clj
   (defmethod print-method InsertOp
     [op ^Writer writer]
     (.write writer "InsertOp")
     (.write writer (str {:key (:key op) :value (:value op) " - " (:tag op)}))))

#?(:clj
   (defmethod print-dup InsertOp
     [op ^Writer writer]
     (.write writer "(tree.messaging/->InsertOp ")
     (.write writer (pr-str (:key op)))
     (.write writer ", ")
     (.write writer (pr-str (:value op)))
     (.write writer ")")))

#?(:clj
   (defmethod pp/simple-dispatch InsertOp
     [op]
     (print op)))

#?(:clj
  (defmethod print-method DeleteOp
    [op ^Writer writer]
    (.write writer "DeleteOp")
    (.write writer (str {:key (:key op)} " - " (:tag op)))))

#?(:clj
   (defmethod print-dup DeleteOp
     [op ^Writer writer]
     (.write writer "(tree.messaging/->DeleteOp ")
     (.write writer (pr-str (:key op)))
     (.write writer ")")))

#?(:clj
   (defmethod pp/simple-dispatch DeleteOp
     [op]
     (print op)))

(defn enqueue
  ([tree msgs]
   (go-try
     (let [deferred-ops (atom [])
           msg-buffers-propagated (<? (enqueue tree msgs deferred-ops))]
       (loop [tree msg-buffers-propagated
              [op & r] @deferred-ops]
         (if op
           (recur (<? (apply-op-to-tree op tree)) r)
           tree)))))
  ([tree msgs deferred-ops]
   (go-try
    (let [tree (core/<?resolve tree)]
       (cond
         (core/data-node? tree) ; need to return ops to apply to the tree proper...
         (do (swap! deferred-ops into msgs)
             tree)
         (<= (+ (count msgs) (count (:op-buf tree)))
             (get-in tree [:cfg :op-buf-size])) ; will there be enough space?
         (-> tree
             (core/dirty!)
             (update-in [:op-buf] into msgs))
         :else ; overflow, should be IndexNode
         (do (assert (core/index-node? tree))
             (loop [[child & children] (:children tree)
                    rebuilt-children (transient [])
                    msgs (vec (sort-by affects-key ;must be a stable sort
                                       core/compare
                                       (concat (:op-buf tree) msgs)))]
               (let [took-msgs (into []
                                     (take-while #(>= 0 (core/compare
                                                         (affects-key %)
                                                         (core/last-key child))))
                                     msgs)
                     extra-msgs (into []
                                      (drop-while #(>= 0 (core/compare
                                                          (affects-key %)
                                                          (core/last-key child))))
                                      msgs)
                     on-the-last-child? (empty? children)

                     ;; Any changes to the current child?
                     new-child
                     (cond
                       (and on-the-last-child? (seq extra-msgs))
                       (<? (enqueue (core/<?resolve child)
                                    (catvec took-msgs extra-msgs)
                                    deferred-ops))
                       (seq took-msgs) ; save a write
                       (<? (enqueue (core/<?resolve child)
                                    (catvec took-msgs)
                                    deferred-ops))
                       :else
                       child)]

                 (if on-the-last-child?
                   (-> tree
                       (assoc :children (-> rebuilt-children
                                            (conj! new-child)
                                            persistent!))
                       (assoc :op-buf [])
                       (core/dirty!))
                   (recur children (conj! rebuilt-children new-child) extra-msgs))))))))))


;;TODO delete in core needs to stop using the index-node constructor to be more
;;careful about how we handle op-bufs during splits and merges.
;;
;;After we've got delete working, lookup, pred, and succ should be fixed
;;
;;broadcast nodes will need IDs so that they can combine during merges...
;;

(defn general-max [e & r]
  ;; fast track for number keys
  (if (number? e)
    (apply max e r)
    (reduce (fn [old elem]
              (if (pos? (core/compare old elem))
                old
                elem))
            e r)))

(defn apply-ops-in-path
  [path]
  (if (>= 1 (count path))
    (:children (peek path))
    (let [ops (->> path
                   (into [] (comp (filter core/index-node?)
                                  (map :op-buf)))
                   (rseq) ; highest node should be last in seq
                   (apply catvec)
                   (sort-by affects-key core/compare)) ;must be a stable sort
          init-path (pop path)
          this-node-index (peek init-path)
          parent (-> init-path pop peek)
          is-first? (zero? this-node-index)
          ;;We'll need to find the smallest last-key of the left siblings along the path
          [left-sibs-on-path is-last?]
          (loop [path path
                 is-last? true
                 left-sibs (transient [])]
            (if (= 1 (count path)) ; are we at the root?
              [(persistent! left-sibs) is-last?]
              (let [init-path (pop path)
                    this-node-index (peek init-path)
                    parent (-> init-path pop peek)
                    is-first? (zero? this-node-index)
                    local-last? (= (-> parent :children count dec)
                                   this-node-index)]
                (if is-first?
                  (recur (pop init-path)
                         (and is-last? local-last?)
                         (if is-first?
                           left-sibs
                           (conj! left-sibs
                                  (nth (:children parent)
                                       (dec this-node-index)))))))))
          left-sibs-min-last (when (seq left-sibs-on-path)
                               (->> left-sibs-on-path
                                    (map core/last-key)
                                    (apply general-max)))
          left-sib-filter (if left-sibs-min-last
                            (drop-while #(>= 0 (core/compare (affects-key %)
                                                             left-sibs-min-last)))
                            identity)
          data-node (peek path)
          my-last (core/last-key data-node)
          right-side-filter (if is-last?
                              identity
                              (take-while #(>= 0 (core/compare (affects-key %) my-last))))
          correct-ops (into [] (comp left-sib-filter right-side-filter) ops)

          ;;We include op if leq my left, and not if leq left's left
          ;;TODO we can't apply all ops, we should ensure to only apply ops whose keys are in the defined range, unless we're the last sibling
          ]
      (reduce (fn [coll op]
                (apply-op-to-coll op coll))
              (:children data-node)
              correct-ops))))

(defn lookup
  ([tree key]
   (lookup tree key nil))
  ([tree key not-found]
   (go-try
     (let [path (<? (core/lookup-path tree key))
           expanded (apply-ops-in-path path)]
       (get expanded key not-found)))))

(defn insert
  [tree key value]
  (enqueue tree [(assoc (->InsertOp key value)
                        :tag (uuid)
                        )]))

(defn delete
  [tree key]
  (enqueue tree [(assoc (->DeleteOp key)
                        :tag (uuid))]))


(case-async
  :none
  (do
    (defn forward-iterator
      "Takes the result of a search and returns an iterator going
   forward over the tree. Does lg(n) backtracking sometimes."
      [path]
      (assert (core/data-node? (peek path)))
      (let [first-elements (apply-ops-in-path path)
            next-elements (lazy-seq
                           (when-let [succ (core/right-successor (pop path))]
                             (forward-iterator succ)))]
        (concat first-elements next-elements)))


    (defn lookup-fwd-iter
      [tree key]
      (let [path (core/lookup-path tree key)]
        (when path
          (drop-while (fn [[k v]]
                        (neg? (core/compare k key)))
                      (forward-iterator path))))))


  :core.async
  (do
    (defn forward-iterator
      "Takes the result of a search and puts the iterated elements onto iter-ch
  going forward over the tree as needed. Does lg(n) backtracking sometimes."
      [iter-ch path start-key]
      (go-try
          (loop [path path]
            (if path
              (let  [_ (assert (core/data-node? (peek path)))
                     elements (drop-while (fn [[k v]]
                                            (neg? (core/compare k start-key)))
                                          (apply-ops-in-path path))]
                (<? (async/onto-chan iter-ch elements false))
                (recur (<? (core/right-successor (pop path)))))
              (async/close! iter-ch)))))

    #?(:clj
       (defn lookup-fwd-iter
         "Compatibility helper to clojure sequences. Please prefer the channel
  interface of forward-iterator, as this function blocks your thread, which
  disturbs async contexts and might lead to poor performance. It is mainly here
  to facilitate testing or for exploration on the REPL."
         [tree key]
         (let [path (<?? (core/lookup-path tree key))
               iter-ch (async/chan)]
           (forward-iterator iter-ch path key)
           (core/chan-seq iter-ch))))))
