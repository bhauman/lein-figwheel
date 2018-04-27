(defproject figwheel-main "0.1.0-SNAPSHOT"
  :description "Figwheel Main - Clojurescript tooling."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel"
        :dir ".."}
  :dependencies
  [[org.clojure/clojure "1.9.0"]
   [org.clojure/clojurescript "1.10.238"]
   [figwheel-repl "0.1.0-SNAPSHOT"]
   [figwheel-core "0.1.0-SNAPSHOT"]
   [ring "1.6.3"]
   [org.eclipse.jetty.websocket/websocket-servlet "9.2.21.v20170120"]
   [org.eclipse.jetty.websocket/websocket-server "9.2.21.v20170120"]
   [hawk "0.2.11"]
   ;; possibly external
   [com.bhauman/rebel-readline-cljs "0.1.2"]
   [expound "0.5.0"]]
  :profiles {:dev {:dependencies [[cider/piggieback "0.3.1"]]
                   :source-paths ["src" "devel"]
                   :resource-paths ["dev_resources" "target"]
                   :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}})
