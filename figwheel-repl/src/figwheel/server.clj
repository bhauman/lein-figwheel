(ns figwheel.server
  (:require
   [clojure.string :as string]
   [ring.middleware.stacktrace :refer [wrap-stacktrace]]
   [clojure.java.browse :refer [browse-url]]
   [figwheel.server.jetty-websocket :as jtw]))

;; TODO this could be smarter and introspect the environment
;; to see what server is available??


;; taken from ring server
(defn try-port
  "Try running a server under one port or a list of ports. If a list of ports
  is supplied, try each port until it succeeds or runs out of ports."
  [port server-fn]
  (if-not (sequential? port)
    (server-fn port)
    (try (server-fn (first port))
         (catch java.net.BindException ex
           (if-let [port (next port)]
             (try-port port server-fn)
             (throw ex))))))

#_((meta #'ring.jetty.adapter/run-jetty))

(defn run-server
  "Start a web server to run a handler.
   Takes all of the ring.jetty.adapter/run-jetty options

   Additional options:
    :port                  - the port or ports to try to run the server on
    :init                  - a function to run before the server starts
    :open-browser?         - if true, open a web browser after the server starts
    :browser-uri           - the path to browse to when opening a browser
    :stacktraces?          - if true, display stacktraces when an exception is thrown
    :stacktrace-middleware - a middleware that handles stacktraces"
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [init destroy join? port] :as options}]]
  (let [options (assoc options :join? false) ;; join needs to be false
        destroy (if destroy (memoize destroy))
        handler (add-stacktraces handler options)]
    (if init (init))
    (try-port port
      (fn [port']
        (let [options (assoc options :port port')
              server  (jtw/run-server* handler options)]
          (println "Figwheel REPL: Started server on port" (server-port server))
          server)))))

(comment
  (require 'figwheel.server.ring)

  (def scratch (atom {}))

  (def serve (jtw/run-jetty
              (figwheel.server.ring/default-stack not-found {})
              {:port 9500 :join? false}))

  (.stop serve)




  )
