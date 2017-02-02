(defproject test-project-no-checkouts "1.0.0"
  :description "A test project for testing Figwheel when there are no checkouts."
  :dependencies [[org.clojure/clojure "1.9.0-alpha15"]
                 [org.clojure/clojurescript "1.9.908"]]
  :source-paths ["src"]
  :cljsbuild {:builds {:dev {:source-paths   ["src"]
                             :compiler       {:main          core
                                              :asset-path    "js/out"
                                              :output-to     "resources/public/js/example.js"
                                              :output-dir    "resources/public/js/out"
                                              :optimizations :none}}}})
