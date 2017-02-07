(defproject example "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/core.async "0.2.395"
                  :exclusions [org.clojure/tools.reader]]

                 ;; NOTE: common clojure server side libraries
                 [ring "1.5.1"]
                 [ring/ring-defaults "0.2.1"]
                 [compojure "1.5.0"]]

  :plugins [[lein-figwheel "0.5.10-SNAPSHOT"]
            [lein-cljsbuild "1.1.5" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :figwheel {:on-jsload "example.core/on-js-reload"
                           :open-urls ["http://localhost:3449"]}

                :compiler {:main example.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/example.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true
                           :preloads [devtools.preload]}}
               {:id "min"
                :source-paths ["src"]
                :compiler {:output-to "resources/public/js/compiled/example.js"
                           :main example.core
                           :optimizations :advanced
                           :pretty-print false}}]}

  :figwheel {;; NOTE: configure figwheel to embed your ring-handler
             :ring-handler example.server-handler/dev-app
             :css-dirs ["resources/public/css"]}

  ;; setting up nREPL for Figwheel and ClojureScript dev
  ;; Please see:
  ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.0"]
                                  [figwheel-sidecar "0.5.10-SNAPSHOT"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

                   ;; need to add dev source path here to get user.clj loaded
                   :source-paths ["src" "dev"]
                   ;; for CIDER
                   ;; :plugins [[cider/cider-nrepl "0.12.0"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}})
