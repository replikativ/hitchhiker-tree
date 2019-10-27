(defproject io.replikativ/hitchhiker-tree "0.1.5-SNAPSHOT"
  :description "A Hitchhiker Tree Library"
  :url "https://github.com/dgrnbrg/hitchhiker-tree"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"]
                 [org.clojure/core.memoize "0.7.1"]
                 [com.taoensso/carmine "2.12.2" :scope "provided"]
                 [org.clojure/core.rrb-vector "0.0.14"]
                 [org.clojure/core.cache "0.7.2"]
                 [io.replikativ/konserve "0.5.1"]]
  :aliases {"bench" ["with-profile" "profiling" "run" "-m" "hitchhiker.bench"]}
  :jvm-opts ["-server" "-Xmx3700m" "-Xms3700m"]
  :profiles {:test
             {:dependencies [[org.clojure/test.check "0.9.0"]]}
             :profiling
             {:main hitchhiker.bench
              :jvm-opts  ^:replace ["-server" "-Xmx3700m" "-Xms3700m"]
              :source-paths ["env/profiling"]
              :dependencies [[criterium "0.4.4"]
                             [org.clojure/tools.cli "0.4.1"]
                             [org.clojure/test.check "0.9.0"]
                             [com.infolace/excel-templates "0.3.3"]]}
             :dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.18"]
                                  [org.clojure/test.check "0.9.0"]
                                  ;; plotting
                                  [aysylu/loom "1.0.2"]
                                  [cheshire "5.8.1"]]
                   :source-paths ["src" "dev" "test"]
                   :plugins [[lein-figwheel "0.5.18"]]
                   :repl-options {; for nREPL dev you really need to limit output
                                  :init (set! *print-length* 50)}}}
  :clean-targets ^{:protect false} ["resources/public/js" "target"]


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
