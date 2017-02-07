(defproject example "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.456"]]

  :plugins [[lein-figwheel "0.5.10-SNAPSHOT"]]

  :source-paths ["src"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]

                :figwheel {:open-urls ["http://localhost:3449/index.html"]}

                :compiler {:main example.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/example.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}

               ;; JavaScript can also be the root of a cljs project!
               ;; This means that you can build a JavaScript project
               ;; in Figwheel. Again you need to follow the
               ;; ClojureScript namespace/location conventions
               {:id "jsroot"
                :source-paths ["src"]

                :figwheel {:open-urls ["http://localhost:3449/index.html"]}

                :compiler {:main example.something
                           :asset-path "js/compiled/someout"
                           :output-to "resources/public/js/compiled/example.js"
                           :output-dir "resources/public/js/compiled/someout"
                           :source-map-timestamp true}}]})
