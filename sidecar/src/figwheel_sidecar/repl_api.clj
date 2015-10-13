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

(defmacro ensure-system [body]
  `(if figwheel-sidecar.repl-api/*figwheel-system*
     ~body
     (println "Figwheel System not itnitialized.\n Please start it with figwheel-sidecar.repl-api/start-figwheel")))

(defn build-once
  "Compiles the builds with the provided build ids (or the current default ids) once."
  [& ids]
  (ensure-system (fs/build-once *figwheel-system* ids)))

(defn clean-builds
  "Deletes the compiled artifacts for the builds with the provided build ids (or the current default ids)."
  [& ids]
  (ensure-system (fs/clean-builds *figwheel-system* ids)))

(defn stop-autobuild
  "Stops the currently running autobuild process."
  [& ids]
  (ensure-system (fs/stop-autobuild *figwheel-system* ids)))

(defn start-autobuild
  "Starts a Figwheel autobuild process for the builds associated with the provided ids (or the current default ids)."
  [& ids]
  (ensure-system (fs/start-autobuild *figwheel-system* ids)))

(defn switch-to-build
  "Stops the currently running autobuilder and starts building the builds with the provided ids."
  [& ids]
  (ensure-system (fs/switch-to-build *figwheel-system* ids)))

(defn reset-autobuild
  "Stops the currently running autobuilder, cleans the current builds, and starts building the default builds again."
  []
  (ensure-system (fs/reset-autobuild *figwheel-system*)))

(defn reload-config
  "Reloads the build config, and resets the autobuild."
  []
  (ensure-system (fs/reload-config *figwheel-system*)))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or the first default id)."
  ([]
   (ensure-system (fs/build-switching-cljs-repl *figwheel-system*)))
  ([id]
   (ensure-system (fs/build-switching-cljs-repl *figwheel-system* id))))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (ensure-system (fs/fig-status *figwheel-system*)))

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
