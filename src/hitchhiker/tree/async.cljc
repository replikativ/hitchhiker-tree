(ns hitchhiker.tree.async
  (:require [clojure.core.rrb-vector.nodes])
  #?(:cljs (:require-macros [hitchhiker.tree.async :refer [patch-nodes]])))


#?(:cljs (patch-nodes))

;; rebind this *before* loading any other
;; hh-tree namespace, so it has effect at
;; macro-expansion time
(def ^:dynamic *async-backend* nil)

;; check cljs macro environment
(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))


#?(:clj
   (defmacro if-cljs
     "Return then if we are generating cljs code and else for Clojure code.
     https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
     [then else]
     (if (cljs-env? &env) then else)))


;; HACK to work around https://github.com/emezeske/lein-cljsbuild/issues/469
;; Taken from https://dev.clojure.org/jira/browse/CRRBV-19.
;; Must be injected first, hence it is spliced in here.
#?(:clj
   (defmacro patch-nodes []
     (when (cljs-env? &env)
       (alter-meta! #'clojure.core.rrb-vector.nodes/ranges dissoc :macro)
       nil)))


#?(:clj
 (defmacro case-async
   "This macro decides which async backend to use at compile time of the code. In
  effect it parametrizes the namespaces for messaging and core.

  Usage:

  (case-async :none (sync-code) :core.async (go-try (my-go-code)))"
   [& case-body-pairs]
     (let [m (into {} (map vec (partition 2 case-body-pairs)))]
       (when (not= #{:none :core.async} (set (keys m)))
         (throw (ex-info "Required async backend code not provided."
                         {:async-backend *async-backend*
                          :code case-body-pairs})))
       (if *async-backend*
         (m *async-backend*)
         (if (cljs-env? &env)
           (m :core.async)
           (m :none))))))
