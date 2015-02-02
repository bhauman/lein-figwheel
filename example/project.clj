(defproject figwheel-example "0.2.2-SNAPSHOT"
  :description "Just an example of using the lein-figwheel plugin"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2727"]
                 [sablono "0.2.16"]
                 [crate "0.2.4"]
                 [jayq "2.4.0"]
                 [figwheel "0.2.3-SNAPSHOT"]
                 ;; for development
                 [figwheel-sidecar "0.2.3-SNAPSHOT"]]
  
  :plugins [[lein-ring "0.8.13"]
            [lein-cljsbuild "1.0.4"]
            [lein-figwheel "0.2.3-SNAPSHOT"]]

  ;; this is used for testing an external server
  :ring { :handler example.server/static-server }
  
  :source-paths ["src"] 

  :clean-targets ^{:protect false} ["resources/public/js/out"
                                    "resources/public/js/example.js"]

  :resource-paths ["resources" "other_resources"]

  :cljsbuild {
              :builds [{ :id "example"
                         :source-paths ["src" "dev" "../support/src"]
                         :compiler {:main example.dev
                                    :asset-path "js/out"
                                    :output-to "resources/public/js/example.js"
                                    :output-dir "resources/public/js/out"
                                    :source-map true
                                    :source-map-timestamp true
                                    :cache-analysis true
                                    :optimizations :none}}
                       { :id "example-admin"
                         :source-paths ["other_src" ]
                         :compiler { :output-to "resources/public/js/compiled/example_admin.js"
                                     :output-dir "resources/public/js/compiled/admin"
                                     :source-map true
                                     :optimizations :none
                                    }}
                       { :id "example-admin-prod"
                         :source-paths ["other_src" ]
                         :compiler { :output-to "resources/public/js/prod/example_admin.js"
                                     :output-dir "resources/public/js/prod/admin"
                                     :optimizations :whitespace
                                    }}]}

  :figwheel {
             :http-server-root "public" ;; default and assumes "resources" 
             :server-port 3449 ;; default
             :css-dirs ["resources/public/css"]
             :open-file-command "emacsclient"
             ;; to disable the repl
             ;; :repl false
             ;; to specify a server logfile
             ;; :server-logfile "tmp/logs/test-server-logfile.log"
             ;; if you want to embed a server in figwheel do it like so:
             ;; :ring-handler example.server/handler
             })
