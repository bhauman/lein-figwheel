(defproject figwheel-repl "0.1.0-SNAPSHOT"
  :description  "Figwheel REPL provides a stable multiplexing REPL for clojurescript"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.10.191"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-defaults "0.3.1"]
                 [co.deps/ring-etag-middleware "0.2.0"]
                 [ring-cors "0.1.12"]]

  ;; for figwheel jetty server server
  :profiles {:dev {:dependencies [[ring "1.6.3"]
                                  [org.eclipse.jetty.websocket/websocket-servlet "9.2.21.v20170120"]
                                  [org.eclipse.jetty.websocket/websocket-server "9.2.21.v20170120"]]}}
  )
