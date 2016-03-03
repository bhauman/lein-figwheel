(defproject figwheel-example "0.2.2-SNAPSHOT"
  :description "Just an example of using the lein-figwheel plugin"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"
                  :exclusions [org.clojure/tools.reader]]
                 [sablono "0.3.5"]
                 [org.omcljs/om "0.8.8"]
                 [ankha "0.1.4"]
                 [datascript "0.9.0"]
                 [com.andrewmcveigh/cljs-time "0.3.11"]
                 [cljs-http "0.1.35"]]
  
  :plugins [[lein-ring "0.8.13" :exclusions [org.clojure/clojure]]
            #_[lein-cljsbuild "1.1.2"]
            [lein-figwheel "0.5.1-SNAPSHOT"]
            #_[lein-npm "0.4.0"]]

  :node-dependencies [[source-map-support "0.2.8"]
                      [express "4.10.7"]
                      [serve-static "1.9.1"]
                      [body-parser "1.12.0"]
                      [type-is "1.6.0"]
                      [ws "0.7.1"]]

  ;; this is used for testing an external server
  :ring { :handler example.server/static-server }
  
  :source-paths ["src"] 

  ;; :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  
  :clean-targets ^{:protect false} ["resources/public/js/out"
                                    "resources/public/js/example.js"
                                    "server_out"
                                    "server_out/todo_server.js"]

  :resource-paths ["resources" "other_resources"]
 
  :cljsbuild {
              :builds [{ :id "example"
                         :source-paths ["src" #_"dev" #_"tests" #_"../support/src"]
                        
                         :figwheel { :websocket-host "localhost"
                                     :on-jsload      example.core/fig-reload
                                    
                                     :on-message     example.core/on-message
                                    ; :debug true
                                    }
                         :compiler { :main example.core
                                     :asset-path "js/out"
                                     :output-to "resources/public/js/example.js"
                                     :output-dir "resources/public/js/out"
                                     :source-map-timestamp true
                                     :libs ["libs_src" "libs_sscr/tweaky.js"]
                                     ;; :externs ["foreign/wowza-externs.js"]
                                     :foreign-libs [{:file "foreign/wowza.js"
                                                     :provides ["wowzacore"]}]
                                     ;; :recompile-dependents true
                                     :optimizations :none}}
                       { :id "example-prod"
                         :source-paths ["src"]
                         :compiler { :main example.core
                                     :asset-path "js/out"
                                     :output-to "resources/public/js/example-prod.js"
                                     :libs ["libs_src" "libs_sscr/tweaky.js"]
                                     :foreign-libs [{:file "foreign/wowza.js"
                                                     :provides ["wowzacore"]}]
                                     :optimizations :whitespace}}
                       {:id "server"
                        :source-paths ["server_src" "../support/src"]
                        :figwheel true
                        :compiler {:main "todo-server.core"
                                   :output-to "server_out/todo_server.js"
                                   :output-dir "server_out"
                                   :target :nodejs
                                   :source-map true}}
                       { :id "example-admin"
                         :source-paths ["other_src" "src" #_"../support/src"]
                         :compiler { :output-to "resources/public/js/compiled/example_admin.js"
                                     :output-dir "resources/public/js/compiled/admin"
                                     :libs ["libs_src" "libs_sscr/tweaky.js"]
                                     ;; :externs ["foreign/wowza-externs.js"]
                                     :foreign-libs [{:file "foreign/wowza.js"
                                                     :provides ["wowzacore"]}]
                                     ;; :recompile-dependents true                                    
                                     :source-map true
                                     :optimizations :none
                                    }}
                       { :id "example-admin-prod"
                         :source-paths ["other_src" ]
                         :compiler { :output-to "resources/public/js/prod/example_admin.js"
                                     :output-dir "resources/public/js/prod/admin"
                                     :optimizations :whitespace
                                    }}]}

  :profiles { :dev { :dependencies [[com.cemerick/piggieback "0.2.1"]
                                    [figwheel-sidecar "0.5.1-SNAPSHOT"]
                                    [org.clojure/tools.namespace "0.2.11"]
                                    [org.clojure/tools.nrepl "0.2.12"]
                                    [leiningen-core "2.5.2"]]
                    
                    :source-paths ["src" "dev"]
                    :repl-options {:init (set! *print-length* 500)}
                    :plugins [[cider/cider-nrepl "0.10.1"]]}}
  
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :figwheel {
             :validate-config true
             :http-server-root "public" ;; default and assumes "resources" 
             :server-port 3449 ;; default
             :css-dirs ["resources/public/css"]
             :open-file-command "emacsclient"
             
             ;; :reload-clj-files {:clj true :cljc true}
             
             ;; Start an nREPL server into the running fighweel
             ;; process

             
             ;; :nrepl-port 7888

             :nrepl-middleware ["cider.nrepl/cider-middleware"
                                #_"refactor-nrepl.middleware/wrap-refactor"
                                "cemerick.piggieback/wrap-cljs-repl"]

             ;; to disable to launched repl 
             ;; :repl false
             ;; to specify a server logfile
             ;; :server-logfile "tmp/logs/test-server-logfile.log"
             ;; if you want to embed a server in figwheel do it like so:
             ;; :ring-handler example.server/handler

             ;; if you need polling instead of FS events
             ;; :hawk-options {:watcher :polling} 
             
             })



