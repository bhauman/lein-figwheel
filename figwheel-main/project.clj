(defproject com.bhauman/figwheel-main "0.1.3"
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
   [com.bhauman/figwheel-repl "0.1.3"]
   [com.bhauman/figwheel-core "0.1.3"]
   [com.bhauman/spell-spec "0.1.0"]
   [ring "1.6.3"]
   [org.eclipse.jetty.websocket/websocket-servlet "9.2.21.v20170120"]
   [org.eclipse.jetty.websocket/websocket-server "9.2.21.v20170120"]
   [binaryage/devtools "0.9.10"]
   [hawk "0.2.11"]
   [expound "0.7.0"]]
  :resource-paths ["helper-resources"]
  :profiles {:dev {:dependencies [[cider/piggieback "0.3.5"]
                                  #_[com.bhauman/rebel-readline-cljs "0.1.4"]]
                   :source-paths ["src" "devel" "dev"]
                   :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}})
