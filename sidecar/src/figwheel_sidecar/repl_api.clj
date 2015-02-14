(ns figwheel-sidecar.repl-api
  (:require
   [figwheel-sidecar.repl :as fr]))

(defn build-once
  "Compiles the builds with the provided build ids (or the current default ids) once."
  [& ids]
  (fr/build-once ids))

(defn clean-build
  "Deletes the compiled artifacts for the builds with the provided build ids (or the current default ids)."
  [& ids]
  (fr/clean-build ids))

(defn stop-autobuild
  "Stops the currently running autobuild process."
  []
  (fr/stop-autobuild))

(defn start-autobuild
  "Starts a Figwheel autobuild process for the builds associated with the provided ids (or the current default ids)."
  [& ids]
  (fr/start-autobuild ids))

(defn switch-to-build
  "Stops the currently running autobuilder and starts building the builds with the provided ids."
  [& ids]
  (fr/switch-to-build ids))

(defn reset-autobuild
  "Stops the currently running autobuilder, cleans the current builds, and starts building the default builds again."
  []
  (fr/reset-autobuild))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or the first default id)."
  ([] (fr/cljs-repl))
  ([id]
   (fr/cljs-repl id)))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (fr/status))

(defn add-dep
  "Attempts to add a maven dependency from clojars."
  [dep]
  (fr/add-dep* dep))

(defn- doc* [v]
  (let [{:keys [name doc arglists]} (meta v)]
    (print name " ")
    (prn arglists)
    (println doc)
    (newline)))

(defn api-help
  "Print out help for the Figwheel REPL api"
  []
  (mapv
   doc*
   [#'cljs-repl
    #'fig-status
    #'start-autobuild
    #'stop-autobuild
    #'build-once
    #'clean-build
    #'switch-to-build
    #'reset-autobuild
    #'api-help
    #'add-dep
    ])
  nil)
