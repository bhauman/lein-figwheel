(ns figwheel-sidecar.repl-api
  (:require
   [clojure.java.io :as io]
   [clojure.repl :refer [doc]]
   [clojure.pprint :as pp]
   [clojure.set :as set]
   [figwheel-sidecar.build-utils :as butils]
   [figwheel-sidecar.system :as fs]
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.build-middleware.notifications :as notify]
   [figwheel-sidecar.components.cljs-autobuild :as cljs-auto]
   #_[figwheel-sidecar.build-utils :as butils]
   [com.stuartsierra.component :as component]))

;; giving this var a uniq name anticipating this library to be
;; included with clojure :use, and system could easily clash
(defonce ^:dynamic *repl-api-system* nil)

(defn system-asserts []
  (config/system-asserts)
  #_(butils/assert-clojurescript-version))

(def ^{:doc (:doc (meta #'fs/start-figwheel!))}
  start-figwheel!
  (fn [& args]
    (when *repl-api-system*
      (alter-var-root #'*repl-api-system* component/stop))
    (alter-var-root #'*repl-api-system* (fn [_] (apply fs/start-figwheel! args)))
    nil))

(defn stop-figwheel!
  "If a figwheel process is running, this will stop all the Figwheel autobuilders and stop the figwheel Websocket/HTTP server."
  []
  (when *repl-api-system*
    (alter-var-root #'*repl-api-system* component/stop)
    nil))

(defn figwheel-running? []
  (or (get-in *repl-api-system* [:figwheel-system :system-running] false)
      (do
        (println "Figwheel System not initialized.\nPlease start it with figwheel-sidecar.repl-api/start-figwheel!")
        nil)))

(defn app-trans
  ([func ids]
   (when (figwheel-running?)
     (let [system (get-in *repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system ids))
       nil)))
  ([func]
   (when (figwheel-running?)
     (let [system (get-in *repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system))
       nil))))

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
  (do
    (fs/print-config
     @(get-in *repl-api-system* [:figwheel-system :system])
     ids)
    nil))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or
the first default id)."
  ([]
   (cljs-repl nil))
  ([id]
   (when (figwheel-running?)
     (fs/cljs-repl (:figwheel-system *repl-api-system*) id))))

(defn repl-env
  "Returns repl-env for use in editors with Piggieback support."
  ([]
   (repl-env nil))
  ([id]
   (when (figwheel-running?)
     (fs/repl-env (:figwheel-system *repl-api-system*) id))))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (app-trans fs/fig-status))

(defn remove-system []
  (stop-figwheel!)
  (alter-var-root #'*repl-api-system* (fn [_] nil)))

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

(defn start-figwheel-from-lein [figwheel-internal-config-data]
  (let [config-data (-> figwheel-internal-config-data
                        config/map->FigwheelInternalConfigData
                        (vary-meta assoc :validate-config false))]
    (when-let [system (fs/start-figwheel! config-data)]
      (alter-var-root #'*repl-api-system* (fn [_] system))
      (if (false? (:repl (config/figwheel-options config-data)))
        (loop [] (Thread/sleep 30000) (recur))
        (if-let [build-id (first (:build-ids (:data config-data)))]
          (fs/cljs-repl (:figwheel-system system) build-id)
          (fs/cljs-repl (:figwheel-system system)))))))

;; new start from lein code here

(defn dispatch-config-source [project-config-source]
  (if (config/figwheel-edn-exists?)
    (config/->figwheel-config-source)
    (config/map->LeinProjectConfigSource project-config-source)))

(defn validate-figwheel-conf [project-config-source options]
  (let [{:keys [file] :as config-data}
        (config/->config-data (dispatch-config-source project-config-source))]
    #_(pp/pprint config-data)
    (config/interactive-validate config-data options)))

(defn validate-and-return-final-config-data [narrowed-project build-ids]
  (when-let [config-data (validate-figwheel-conf narrowed-project {})]
    (let [{:keys [data] :as figwheel-internal-data}
          (-> config-data
              config/config-data->figwheel-internal-config-data
              config/prep-builds)
          {:keys [figwheel-options all-builds]} data
          ;; TODO this is really outdated
          errors (config/check-config figwheel-options
                                      (config/narrow-builds*
                                       all-builds
                                       build-ids))
          figwheel-internal-final
          (config/populate-build-ids figwheel-internal-data build-ids)]
      #_(pp/pprint figwheel-internal-final)
      (if (empty? errors)
        figwheel-internal-final
        (do (mapv println errors) false)))))

(defn resolve-hook-fn [ky hook]
  (when hook
    (if-let [hook-fn (utils/require-resolve-handler hook)]
      (if (or (fn? hook-fn) (and (var? hook-fn) (fn? @hook-fn)))
        hook-fn
        (println "Figwheel: your" (pr-str ky) "function is not a function - " (pr-str hook)))
      (println "Figwheel: unable to resolve your" (pr-str ky) "function - " (pr-str hook)))))

(defn call-hook-fn [ky hook-fn]
  (do (println "Figwheel: calling your" (pr-str ky) "function - "(pr-str hook-fn))
      (hook-fn)))

(defn launch-init [figwheel-internal-final]
  (when-let [hook-fn
             (resolve-hook-fn
              :init
              (:init (config/figwheel-options figwheel-internal-final)))]
    (call-hook-fn :init hook-fn)))

(defn wrap-destroy [destroy-hook thunk]
  (let [destroy-hook' (memoize #(call-hook-fn :destroy destroy-hook))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. destroy-hook'))
    (thunk)
    (destroy-hook')))

(defn launch-from-lein [narrowed-project build-ids]
  (when-let [figwheel-internal-final
             (validate-and-return-final-config-data narrowed-project build-ids)]
    (launch-init figwheel-internal-final)
    (if-let [destroy-hook (resolve-hook-fn
                           :destroy
                           (:destroy (config/figwheel-options figwheel-internal-final)))]
      (wrap-destroy destroy-hook #(start-figwheel-from-lein figwheel-internal-final))
      (start-figwheel-from-lein figwheel-internal-final))))

(defn build-once-from-lein [narrowed-project build-ids]
  (when-let [config-data (validate-figwheel-conf narrowed-project {})]
    (let [figwheel-internal-final
          (config/config-data->prepped-figwheel-internal config-data)
          all-builds (config/all-builds figwheel-internal-final)
          builds (if (not-empty build-ids)
                   (filter #((set build-ids) (:id %)) all-builds)
                   all-builds)
          unknown-ids (set/difference
                       (set build-ids)
                       (set (map :id all-builds)))]

      (when (empty? builds)
        (println "Figwheel: Didn't find any the supplied build ids in the configuration.")
        (println "Unknown build ids: " (pr-str (vec unknown-ids)))
        (println "The build names available in your config: " (pr-str (map :id all-builds))))
      (doseq [build (map butils/add-compiler-env builds)]
        ((-> cljs-auto/cljs-build
             cljs-auto/figwheel-start-and-end-messages
             notify/print-hook
             cljs-auto/color-output)
         {:figwheel-server
          {:ansi-color-output (config/use-color?
                               (config/figwheel-options figwheel-internal-final))}
          :build-config build})))))

(comment
  (def proj (config/->config-data (config/->lein-project-config-source)))
  (def error-proj (assoc-in (:data proj) [:figwheel :server-port]
                            "ASDFASDF"))
  (config/figwheel-options proj)
  (launch-from-lein (:data proj) ["dev"])

  (launch-from-lein (:data proj) ["example-prod"])

  )
