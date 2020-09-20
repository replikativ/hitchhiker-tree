(defproject io.replikativ/hitchhiker-tree "0.1.8"
  :description "A Hitchhiker Tree Library"
  :url "https://github.com/replikativ/hitchhiker-tree"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773" :scope "provided"]
                 [org.clojure/core.memoize "1.0.236"]
                 [com.taoensso/carmine "2.20.0" :scope "provided"]
                 [org.clojure/core.rrb-vector "0.1.1"]
                 [org.clojure/core.cache "1.0.207"]
                 [io.replikativ/konserve "0.6.0-alpha1"]]
  :aliases {"bench" ["with-profile" "profiling" "run" "-m" "hitchhiker.bench"]}
  :jvm-opts ["-server" "-Xmx3700m" "-Xms3700m"]
  :profiles {:test
             {:dependencies [[org.clojure/test.check "0.9.0"]]}
             :profiling
             {:main hitchhiker.bench
              :jvm-opts  ^:replace ["-server" "-Xmx3700m" "-Xms3700m"]
              :source-paths ["env/profiling"]
              :dependencies [[criterium "0.4.6"]
                             [org.clojure/tools.cli "1.0.194"]
                             [org.clojure/test.check "1.1.0"]
                             [com.infolace/excel-templates "0.3.3"]]}
             :dev {:dependencies [[binaryage/devtools "1.0.2"]
                                  [figwheel-sidecar "0.5.20"]
                                  [org.clojure/test.check "1.1.0"]
                                  ;; plotting
                                  [aysylu/loom "1.0.2"]
                                  [cheshire "5.10.0"]]
                   :source-paths ["src" "dev" "test"]
                   :plugins [[lein-figwheel "0.5.20"]]
                   :repl-options {; for nREPL dev you really need to limit output
                                  :init (set! *print-length* 50)}}}
  :clean-targets ^{:protect false} ["resources/public/js" "target"]

  :deploy-repositories [["clojars"  {:url "https://clojars.org/repo"
                                     :username :env/clojars_username
                                     :password :env/clojars_password
                                     :sign-releases false}]]

  :cljsbuild {:builds
              [{:id "dev"
                :figwheel true
                :source-paths ["src"]
                :compiler {:main hitchhiker.tree.core
                           :asset-path "js/out"
                           :output-to "resources/public/js/core.js"
                           :output-dir "resources/public/js/out" }}
               ;; inspired by datascript project.clj
               {:id "test"
                :source-paths ["src" "test"]
                :compiler {
                           :main          hitchhiker.konserve-test
                           :output-to     "target/test.js"
                           :output-dir    "target/none"
                           :optimizations :none
                           :source-map    true
                           :recompile-dependents false
                           :parallel-build true}}]}

  :plugins [[lein-figwheel "0.5.18"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]])
