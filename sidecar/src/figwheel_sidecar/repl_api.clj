(ns figwheel-sidecar.repl-api
  (:require
   [figwheel-sidecar.system :as fs]
   [com.stuartsierra.component :as component]))

(defonce ^:dynamic *figwheel-system* (atom nil))

#_(def temp-config
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      :nrepl-port 7888}
   :build-ids  ["example"]
   :all-builds (fs/get-project-builds)})

(defn start-figwheel!
  "If you aren't connected to an env where fighweel is running already,
  this method will start the figwheel server with the passed in build info."
  ([{:keys [figwheel-options all-builds build-ids] :as autobuild-options}]
   (when @*figwheel-system*
     (swap! *figwheel-system* component/stop))
   (reset! *figwheel-system* (fs/start-figwheel! autobuild-options)))
  ([]
   ;; TODO automatically pull in config if atom is empty
   ;; we can check if there is a project.clj
   ;; and we can pull in the config
   (swap! *figwheel-system* component/start)))

#_ (start-figwheel! temp-config)

(defn stop-figwheel!
  "If a figwheel process is running, this will stop all the Figwheel autobuilders and stop the figwheel Websocket/HTTP server."
  []
  (when @*figwheel-system*
    (swap! *figwheel-system* component/stop)))

(comment
  ;; example usage
  (require 'figwheel-sidecar.repl-api)

  (in-ns 'figwheel-sidecar.repl-api)
  
  (start-figwheel!
   {:figwheel-options {}
    :build-ids ["example"]
    :all-builds [{ :id "example"
                  :source-paths ["src" "dev"]
                  :compiler {:main "example.dev"
                             :asset-path "js/out"
                             :output-to "resources/public/js/example.js"
                             :output-dir "resources/public/js/out"
                             :source-map true
                             :source-map-timestamp true
                             :cache-analysis true
                             :optimizations :none}}]})
  )

(defn figwheel-running? []
  (or @*figwheel-system*
      (do
        (println "Figwheel System not itnitialized.\n Please start it with figwheel-sidecar.repl-api/start-figwheel")
        nil)))

(defn app-trans
  ([func ids]
   (when figwheel-running?
     (reset! *figwheel-system* (func @*figwheel-system* ids))))
  ([func]
   (when figwheel-running?
     (reset! *figwheel-system* (func @*figwheel-system*)))))

(defn build-once
  "Compiles the builds with the provided build ids
(or the current default ids) once."
  [& ids]
  (app-trans fs/build-once ids))

(defn clean-builds
  "Deletes the compiled artifacts for the builds with the provided
build ids (or the current default ids)."
  [& ids]
  (app-trans fs/clean-builds ids))

(defn stop-autobuild
  "Stops the currently running autobuild process."
  [& ids]
  (app-trans fs/stop-autobuild ids))

(defn start-autobuild
  "Starts a Figwheel autobuild process for the builds associated with
the provided ids (or the current default ids)."
  [& ids]
  (app-trans fs/start-autobuild ids))

(defn switch-to-build
  "Stops the currently running autobuilder and starts building the
builds with the provided ids."
  [& ids]
  (app-trans fs/switch-to-build ids))

(defn reset-autobuild
  "Stops the currently running autobuilder, cleans the current builds,
and starts building the default builds again."
  []
  (app-trans fs/reset-autobuild))

(defn reload-config
  "Reloads the build config, and resets the autobuild."
  []
  (app-trans fs/reload-config))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or
the first default id)."
  ([]
   (cljs-repl nil))
  ([id]
   (when (figwheel-running?)
     (fs/build-switching-cljs-repl *figwheel-system* id))))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (app-trans fs/fig-status))

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
    #'clean-builds
    #'switch-to-build
    #'reset-autobuild
    #'reload-config    
    #'api-help])
  nil)
