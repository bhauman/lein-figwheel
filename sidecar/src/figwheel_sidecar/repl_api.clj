(ns figwheel-sidecar.repl-api
  (:require
   [clojure.repl :refer [doc]]
   [figwheel-sidecar.system :as fs]
   [figwheel-sidecar.config :as config]
   [com.stuartsierra.component :as component]))

;; giving this var a uniq name anticipating this library to be
;; included with clojure :use, and system could easily clash
(defonce ^:dynamic *repl-api-system* (atom nil))

#_(def temp-config
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      :nrepl-port 7888}
   :build-ids  ["example"]
   :all-builds (fs/get-project-builds)})

(defn start-figwheel!
  "If you aren't connected to an env where fighweel is running already,
  this method will start the figwheel server with the passed in build info."
  ([{:keys [figwheel-options all-builds build-ids] :as autobuild-options}]
   (when @*repl-api-system*
     (swap! *repl-api-system* component/stop))
   (reset! *repl-api-system* (fs/start-figwheel! autobuild-options)))
  ([]
   (if @*repl-api-system*
     (swap! *repl-api-system* component/start)
     ;; if no system exists try to read in a configuration
     (start-figwheel! (config/fetch-config)))))

#_ (start-figwheel! temp-config)

(defn stop-figwheel!
  "If a figwheel process is running, this will stop all the Figwheel autobuilders and stop the figwheel Websocket/HTTP server."
  []
  (when @*repl-api-system*
    (swap! *repl-api-system* component/stop)))

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
  (or @*repl-api-system*
      (do
        (println "Figwheel System not itnitialized.\n Please start it with figwheel-sidecar.repl-api/start-figwheel")
        nil)))

(defn app-trans
  ([func ids]
   (when figwheel-running?
     (let [system (get-in @*repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system ids)))))
  ([func]
   (when figwheel-running?
     (let [system (get-in @*repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system))))))

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

(defn print-config
  "Prints out the build configs currently focused or optionally the
  configs of the ids provided."
  [& ids]
  (fs/print-config @*repl-api-system* ids))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or
the first default id)."
  ([]
   (cljs-repl nil))
  ([id]
   (when (figwheel-running?)
     (fs/build-switching-cljs-repl *repl-api-system* id))))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (app-trans fs/fig-status))

(defn api-help
  "Print out help for the Figwheel REPL api"
  []
  (doc cljs-repl)
  (doc fig-status)
  (doc start-autobuild)
  (doc stop-autobuild)
  (doc build-once)
  (doc clean-builds)
  (doc switch-to-build)
  (doc reset-autobuild)
  (doc reload-config)
  (doc api-help))

(defn start-figwheel-from-lein [{:keys [figwheel-options all-builds build-ids] :as options}]
  (let [system (fs/start-figwheel! options)]
    (reset! figwheel-sidecar.repl-api/*repl-api-system* system)
    (if (false? (:repl figwheel-options))
      (loop [] (Thread/sleep 30000) (recur))
      (fs/build-switching-cljs-repl (:figwheel-system system)))))
