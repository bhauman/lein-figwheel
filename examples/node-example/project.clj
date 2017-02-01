(defproject node-example "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/clojurescript "1.9.229"]]

  :plugins [[lein-figwheel "0.5.10-SNAPSHOT"]]

  :source-paths ["src"]

  :cljsbuild {:builds
              [{:id "node-dev"
                :source-paths ["src"]
                :figwheel true
                :compiler {:main node-example.core
                           ;; in order to call node from root of project
                           ;; need to have :asset-path be the same as :output-dir
                           :asset-path "target/js/compiled/out"
                           ;; the script you will run with node
                           :output-to  "target/js/compiled/node_example.js"
                           :output-dir "target/js/compiled/out"
                           :source-map-timestamp true
                           ;; !!! need to set the target to :nodejs !!!
                           :target :nodejs}}]}

  :profiles {:dev {:dependencies [[figwheel-sidecar "0.5.10-SNAPSHOT"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
)
