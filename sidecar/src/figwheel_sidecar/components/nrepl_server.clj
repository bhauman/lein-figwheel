(ns figwheel-sidecar.components.nrepl-server
  (:require
   [figwheel-sidecar.utils :as utils]
   [clojure.tools.nrepl.server :as nrepl-serv]
   [com.stuartsierra.component :as component]   ))

(defn start-nrepl-server
  [figwheel-options autobuild-options]
  (when (:nrepl-port figwheel-options)
    (let [middleware (or
                      (:nrepl-middleware figwheel-options)
                      ["cemerick.piggieback/wrap-cljs-repl"])
          resolve-mw (fn [name]
                       (let [s (symbol name)
                             ns (symbol (namespace s))]
                         (if (and
                              (utils/require? ns)
                              (resolve s))
                           (let [var (resolve s)
                                 val (deref var)]
                             (if (vector? val)
                               (map resolve val)
                               (list var)))
                           (println (format "WARNING: unable to load \"%s\" middleware" name)))))
          middleware (mapcat resolve-mw middleware)]
      (nrepl-serv/start-server
       :port (:nrepl-port figwheel-options)
       :bind (:nrepl-host figwheel-options)
       :handler (apply nrepl-serv/default-handler middleware)))))

(defrecord NreplComponent []
  component/Lifecycle
  (start [this]
    (if (not (:running-nrepl-server this))
      (do
        (println "Figwheel: Starting nREPL server on port:" (:nrepl-port this))
        (assoc this :running-nrepl-server (start-nrepl-server this nil)))
      (do
        (println "Figwheel: nREPL server already running")
        this)))
  ;; consider not stopping the NreplComponent
  (stop [this]
        (when (:running-nrepl-server this)
          (println "Figwheel: Stopped nREPL server")
          (nrepl-serv/stop-server (:running-nrepl-server this)))
    (dissoc this :running-nrepl-server)))

(defn nrepl-server-component
  "  Creates an nREPL server component and attempts to load
  some default middleware to support a cljs workflow.

  This function takes a map with the following keys
  Options:
  :nrepl-port   the port to start the server on; must exist or the server wont start
  :nrepl-host   an optional network host to open the port on
  :nrepl-middleware a optional list of nREPL middleware to include

  This function will attempt to require/load the
  cemerick.piggieback/wrap-cljs-repl middleware which is needed to
  start a ClojureSript REPL over nREPL."
  [options]
  (map->NreplComponent options))
