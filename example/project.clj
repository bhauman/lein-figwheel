(defproject figwheel-example "0.2.0-SNAPSHOT"
  :description "Just an example of using the lein-figwheel plugin"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2496"]
                 [sablono "0.2.16"]
                 [crate "0.2.4"]
                 [jayq "2.4.0"]
                 [figwheel "0.2.0-SNAPSHOT"]]

  :plugins [[lein-ring "0.8.13"]
            [lein-cljsbuild "1.0.3"]
            [lein-figwheel "0.2.0-SNAPSHOT"]]

  ;; this is used for testing an external server
  :ring { :handler example.server/static-server }
  
  :source-paths ["src"] 
  
  :resource-paths ["resources" "other_resources"]
  
  :cljsbuild {
              :builds [{ :id "example"
                         :source-paths ["src" "../support/src"]
                         :compiler { :output-to "resources/public/js/compiled/example.js"
                                     :output-dir "resources/public/js/compiled/out"
                                     :source-map true
                                     ;; :reload-non-macro-clj-files false
                                     :optimizations :none}}]}

  :figwheel {
             :http-server-root "public" ;; default and assumes "resources" 
             :server-port 3449 ;; default
             :css-dirs ["resources/public/css"]
             :open-file-command "emacsclient"
             ;; if you want to embed a server in figwheel do it like so:
             #_:ring-handler #_example.server/handler
             })
