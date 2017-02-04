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
                 [ring/ring-defaults "0.2.1"]
                 [compojure "1.5.0"]]

  :plugins [[lein-figwheel "0.5.10-SNAPSHOT"]
            [lein-cljsbuild "1.1.5" :exclusions [[org.clojure/clojure]]]

            ;; NOTE: lein-ring plugin allows you to start a server
            ;; with a simple `lein ring`
            ;; see --> https://github.com/weavejester/lein-ring
            
            [lein-ring "0.10.0"]]

  ;; NOTE: configure lein-ring to serve your ring handler
  ;; this config is for production deployment
  :ring {:handler example.server-handler/app
         ;; the :init config key is very helpful especially in the context
         ;; of production
         ;; :init example.server/init
         }

  :source-paths ["src"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :figwheel {:on-jsload "example.core/on-js-reload"
                           :open-urls ["http://localhost:3000/index.html"]}

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

  :figwheel {;; NOTE: configure figwheel to start your server
             ;; see dev/user.clj if you are using "lein figwheel" to start your
             ;; figwheel process
             :init     user/start-server
             ;; not required but good to know about
             :destroy  user/stop-server
             :css-dirs ["resources/public/css"]}

  ;; NOTE: compile and package up your project for deployment
  ;; with `lein package`
  :aliases {"package" ["do" "clean"
                       ["cljsbuild" "once" "min"]
                       ["ring" "uberjar"]]}
  
  ;; setting up nREPL for Figwheel and ClojureScript dev
  ;; Please see:
  ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.0"]
                                  [figwheel-sidecar "0.5.10-SNAPSHOT"]
                                  [com.cemerick/piggieback "0.2.1"]

                                  ;; NOTE supply server library for dev time
                                  ;; see --> dev/user.clj
                                  [ring-server "0.4.0"]]
                   :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]
                   
                   ;; need to add dev source path here to get user.clj loaded
                   :source-paths ["src" "dev"]
                   ;; for CIDER
                   ;; :plugins [[cider/cider-nrepl "0.12.0"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}})
