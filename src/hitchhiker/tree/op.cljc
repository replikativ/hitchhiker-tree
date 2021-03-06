(ns hitchhiker.tree.op
  "An operation is an object with a few functions
  1. It has a function that it applies to the tree to apply its effect
  In the future, it could also have
  2. It has a promise which can be filled with the end result
     (more memory but faster results for repeat queries)")

(defprotocol IOperation
  (-insertion-ts [op] "Returns totally ordered timestamp at which this operation has been created.")
  (-affects-key [op] "Which key this affects--currently must be a single key.")
  (-apply-op-to-coll [op coll] "Applies the operation to the collection.")
  (-apply-op-to-tree [op tree] "Applies the operation to the tree. Returns go-block."))
