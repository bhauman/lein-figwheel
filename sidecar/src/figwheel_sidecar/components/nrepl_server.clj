(ns figwheel-sidecar.components.nrepl-server
  (:require
   [figwheel-sidecar.utils :as utils]
   [clojure.tools.nrepl.server :as nrepl-serv]
   [com.stuartsierra.component :as component]   ))

(def nrepl-port-file
  "The file that contains the nREPL port number."
  (java.io.File. ".nrepl-port"))

(defn write-nrepl-port-file
  "Write the `port` of the nREPL server to `nrepl-port-file`."
  [port]
  (when port
    (try
      (spit nrepl-port-file (str port))
      (catch Exception e
        (println (format "Figwheel: Can't write nREPL server port to `%s`: %s"
                         (str nrepl-port-file) (.getMessage e)))))))

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
          middleware (mapcat resolve-mw middleware)
          server (nrepl-serv/start-server
                  :port (:nrepl-port figwheel-options)
                  :bind (:nrepl-host figwheel-options)
                  :handler (apply nrepl-serv/default-handler middleware))]
      (write-nrepl-port-file (:nrepl-port figwheel-options))
      server)))

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
          (nrepl-serv/stop-server (:running-nrepl-server this))
          (.delete nrepl-port-file))
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
