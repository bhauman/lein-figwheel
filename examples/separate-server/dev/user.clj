(ns user
  (:require
   [figwheel-sidecar.repl-api :as f]
   [example.server-handler :as server-handler]
   [ring.server.standalone :as ring-server]))

;; user is a namespace that the Clojure runtime looks for and loads if
;; its available

;; You can place helper functions in here. This is great for starting
;; and stopping your webserver and other development services

;; The definitions in here will be available if you run "lein repl" or launch a
;; Clojure repl some other way

;; You have to ensure that the libraries you :require are listed in the :dependencies
;; in the project.clj

;; Once you start down this path
;; you will probably want to look at
;; tools.namespace https://github.com/clojure/tools.namespace
;; and Component https://github.com/stuartsierra/component

;; or the exciting newcomer https://github.com/weavejester/integrant

;; DEVELOPMENT SERVER HELPERS: starting and stopping a development server in the REPL

(defonce dev-state (atom {}))

(defn start-server
  "Starts a ring server for your developement application"
  []
  (if-not (:ring-server @dev-state)
    (swap! dev-state assoc :ring-server
           ;; NOTE using var for better REPL reloading dev experience
           (ring-server/serve #'server-handler/dev-app {:open-browser? false}))
    (println "Server already running!")))

(defn stop-server
  "Stops the running ring server."
  []
  (when-let [ring-server (:ring-server @dev-state)]
    (swap! dev-state dissoc :ring-server)
    (println "Stopping ring server!")
    (.stop ring-server)))

;; FIGWHEEL HELPERS: starting and stopping figwheel in the REPL

(defn fig-start
  "This starts the figwheel server and watch based auto-compiler."
  []
  (f/start-figwheel!))

(defn fig-stop
  "Stop the figwheel server and auto-compiler."
  []
  (f/stop-figwheel!))

;; if you are in an nREPL environment you will need to make sure you
;; have setup piggieback for this to work
(defn cljs-repl
  "Launch a ClojureScript REPL that is connected to your build and host environment."
  []
  (f/cljs-repl))
