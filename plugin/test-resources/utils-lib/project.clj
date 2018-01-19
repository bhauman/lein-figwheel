(defproject utils-lib "1.0.0"
  :description "A test project to be consumed as a checkouts project."
  :dependencies [[org.clojure/clojure "1.9.0-alpha15"]
                 [org.clojure/clojurescript "1.9.908"]]
  :source-paths ["src"]
  :cljsbuild {:builds {:dev {:source-paths ["src"]
                             :compiler     {:main          utils
                                            :asset-path    "js/out"
                                            :output-to     "resources/public/js/example.js"
                                            :output-dir    "resources/public/js/out"
                                            :optimizations :none}}}})
