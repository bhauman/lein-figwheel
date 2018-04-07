(defproject figwheel-repl "0.1.0-SNAPSHOT"
  :description  "Figwheel REPL provides a stable multiplexing REPL for clojurescript"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.10.191"]]

  :profiles {:dev {:dependencies [[ring "1.6.3"]
                                  [ring-cors "0.1.11"]
                                  [ring/ring-defaults "0.3.1"]
                                  [co.deps/ring-etag-middleware "0.2.0"]
                                  [org.eclipse.jetty.websocket/websocket-servlet "9.2.21.v20170120"]
                                  [org.eclipse.jetty.websocket/websocket-server "9.2.21.v20170120"]]}}
  )
