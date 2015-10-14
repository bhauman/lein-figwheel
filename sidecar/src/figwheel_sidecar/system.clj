(ns figwheel-sidecar.system
  (:require
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :refer [repl-println] :as frepl]   
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.watching :refer [watcher]]
   [figwheel-sidecar.injection]
   [figwheel-sidecar.notifications]
   [figwheel-sidecar.clj-reloading]
   [figwheel-sidecar.javascript-reloading]   

   [com.stuartsierra.component :as component]

   [cljs.build.api :as bapi]
   [cljs.env :as env]
   [cljs.closure]

   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.core.async :refer [go-loop chan <! close! put!]]   
   [clojure.tools.nrepl.server :as nrepl-serv]   
   [clojure.set :refer [difference union]]
   [clojure.string :as string]))

;; helpers 

;; TODO does this belong here
(defn clean-cljs-build* [{:keys [output-to output-dir] :as build-options}]
  (when (and output-to output-dir)
    (let [clean-file (fn [s] (when (.exists s) (.delete s)))]
      (mapv clean-file
            (cons (io/file output-to)
                  (reverse (file-seq (io/file output-dir))))))))

(declare add-compiler-env)

;; TODO does this belong here?
(defn get-project-builds []
  (into (array-map)
        (map
         (fn [x]
           [(:id x)
            (add-compiler-env x)])
         (frepl/get-project-cljs-builds))))

;; build functions 

;; bapi/inputs should work but something wierd is happening
(defrecord CompilableSourcePaths [paths]
  cljs.closure/Compilable
  (-compile [_ opts]
    (reduce (fn [accum v]
              (let [o (cljs.closure/-compile v opts)]
                (if (seq? o)
                  (concat accum o)
                  (conj accum o))))
            []
            paths)))

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (CompilableSourcePaths. (:source-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

;; Start and end messages

(let [reset-color "\u001b[0m"
      foreground-green "\u001b[32m"
      elapsed
      (fn [started-at]
        (let [elapsed-us (- (System/currentTimeMillis) started-at)]
          (with-precision 2
            (str (/ (double elapsed-us) 1000) " seconds"))))]
  (defn figwheel-start-and-end-messages [build-fn]
    (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
      (let [started-at (System/currentTimeMillis)
            {:keys [build-options source-paths]} build-config
            {:keys [output-to]} build-options]
        ;; print start message
        (println (str reset-color "Compiling \""
                      output-to
                      "\" from " (pr-str source-paths) "..."))
        (flush)
                                        ; build
        (build-fn build-state)
                                        ; print end message
        (println (str foreground-green
                      "Successfully compiled \""
                      output-to
                      "\" in " (elapsed started-at) "." reset-color))
        (flush)))))

(def figwheel-build
  (-> cljs-build
      figwheel-sidecar.injection/build-hook
      figwheel-sidecar.notifications/build-hook
      figwheel-sidecar.clj-reloading/build-hook
      figwheel-sidecar.javascript-reloading/build-hook
      figwheel-start-and-end-messages))

;; server component

(defrecord FigwheelServer []
  component/Lifecycle
  (start [this]
    (if-not (:http-server this)
      (do
        (map->FigwheelServer (fig/start-server this)))
      this))
  (stop [this]
    (when (:http-server this)
      (println "Figwheel: Stopping Server")
      (fig/stop-server this))
    (dissoc this :http-server)))

;; synchronous execution component
;; this needed because watchers run asynchronously and can cause
;; builds to run in parallel, must sync them
;; TODO dosync is something I should look at eh?
;; refactor into a blocking queue arrangement?

;; use dosync and a ref

(defrecord SyncExecutor []
  component/Lifecycle
  (start [this]
    (if-not (:exec-channel this)
      (let [in (chan)]
        (go-loop []
          (when-let [thunk (<! in)]
            (when (fn? thunk)
              ;; try catch?
              (thunk))
            (recur)))
        (assoc this :exec-channel in))
      this))
  (stop [this]
    (when (:exec-channel this)
      (close! (:exec-channel this)))
    (dissoc this :exec-channel)))

(defn sync-exec [{:keys [exec-channel]} thunk]
  (if exec-channel
    (put! exec-channel thunk)
    (thunk)))

;; CLJS watching component

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     source-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

(defn build-handler [{:keys [figwheel-server build-config cljs-build-fn] :as watcher}
                     files]
  ((or cljs-build-fn figwheel-build)
   (assoc watcher :changed-files files)))

(defrecord CLJSWatcher [build-config figwheel-server log-writer sync-executor]
  component/Lifecycle
  (start [this]
    (if-not (:file-watcher this)
      (do
        (println "Figwheel: Watching build -" (:id build-config))
        (flush)
        ;; setup
        (figwheel-sidecar.injection/delete-connect-scripts! [build-config])
        ;; TODO this should be conditional based on a flag
        #_(clean-cljs-build* (:build-options build-config))
        ;; initial build only needs the injection and the
        ;; start and end messages
        ((-> cljs-build
             figwheel-sidecar.injection/build-hook
             figwheel-start-and-end-messages) this)
        (let [log-writer (or log-writer (io/writer "figwheel_server.log" :append true))]
          (assoc this
                 :file-watcher
                 (watcher (source-paths-that-affect-build build-config)
                          (fn [files]
                            (sync-exec
                             sync-executor
                             (fn []
                               (binding [*out* log-writer
                                         *err* log-writer]
                                 (#'build-handler this files)))))))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build -" (:id build-config))
      (flush)
      (reset! (:file-watcher this) true))
    (dissoc this :file-watcher)))

;; css changes component

(defn handle-css-notification [figwheel-server files]
  (let [changed-css-files (filter #(.endsWith % ".css") files)]
    (when (not-empty changed-css-files)
      (fig/notify-css-file-changes figwheel-server changed-css-files)
      (doseq [f files]
        (println "sending changed CSS file:" (:file f))))))

(defrecord CSSWatcher [css-dirs figwheel-server log-writer sync-executor]
  component/Lifecycle
  (start [this]
         (if (not (:css-watcher-quit this))
           (do
             (if (:css-dirs this)
               (let [log-writer (or log-writer (io/writer "figwheel_server.log" :append true))]
                 (println "Figwheel: Starting CSS watcher for dirs " (pr-str (:css-dirs this)))
                 (assoc this :css-watcher-quit
                        (watcher (:css-dirs this)
                                 (fn [files]
                                   (sync-exec
                                    sync-executor
                                    (fn []
                                      (binding [*out* log-writer]
                                        (#'handle-css-notification
                                         (:figwheel-server this) files))))))))
               (do
                 (println "Figwheel: No CSS directories configured")
                 this)))
           (do
             (println "Figwheel: Already watching CSS")
             this)))
  (stop [this]
        (when (:css-watcher-quit this)
          (println "Figwheel: Stopped watching CSS")
          (reset! (:css-watcher-quit this) true))
    (dissoc this :css-watcher-quit)))


;; nrepl component only really useful in a development env
;; considering making it idempotent so that it's only launched once

(defn require? [symbol]
  (try (require symbol) true (catch Exception e false)))

(defn start-nrepl-server [figwheel-options autobuild-options]
  (when (:nrepl-port figwheel-options)
    (let [middleware (or
                      (:nrepl-middleware figwheel-options)
                      ["cemerick.piggieback/wrap-cljs-repl"])
          resolve-mw (fn [name]
                       (let [s (symbol name)
                             ns (symbol (namespace s))]
                         (if (and
                              (require? ns)
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

;; figwheel system based on components defined above

;; TODO
(comment

  ;; secondary

  make sure the prep-builds is idempotent
  
  figwheel-server level :reload-clj and :reload-cljc config flags
  
  clearly defined server message sending api

  example html reload
)

(defn require-resolve-var [handler]
  (when handler
    (let [h (symbol handler)]
      (require (symbol (namespace h)))
      (resolve h))))

(defn get-cljs-build-fn [{:keys [cljs-build-fn]}]
  (when cljs-build-fn
    (if (fn? cljs-build-fn)
      cljs-build-fn
      (require-resolve-var cljs-build-fn))))

(declare build-config->key)

;; all-builds need to be prepped before sending it in here
(defn create-figwheel-system* [{:keys [figwheel-options all-builds build-ids]}]
  (let [logfile-path    (or (:server-logfile figwheel-options) "figwheel_server.log")
        log-writer      (if (false? (:repl figwheel-options) )
                          *out*
                          (io/writer logfile-path :append true))
        builds-to-start (config/narrow-builds* all-builds build-ids)
        cljs-build-fn   (or (get-cljs-build-fn figwheel-options) figwheel-build)

        ;; all builds needs to be an array map 
        all-builds      (into (array-map)
                              (map (juxt :id identity)
                                   (if (map? all-builds) (vals all-builds) all-builds)))]
    (apply
     component/system-map
     (concat
      [:builds all-builds ;; this needs to be an array map
       :cljs-build-fn cljs-build-fn
       :log-writer log-writer
       :sync-executor (SyncExecutor.)
       :figwheel-server (map->FigwheelServer figwheel-options)]
      (mapcat (fn [build-config]
                [(build-config->key build-config)
                 (component/using
                  (map->CLJSWatcher {:build-config build-config})
                  [:figwheel-server :log-writer :sync-executor :cljs-build-fn])])
              builds-to-start)
      (when (:nrepl-port figwheel-options)
        [:nrepl-server
         (map->NreplComponent
          (select-keys figwheel-options [:nrepl-port
                                         :nrepl-host
                                         :nrepl-middleware]))])
      (when-let [css-dirs (:css-dirs figwheel-options)]
        ;; TODO ensure directory exists
        [:css-watcher
         (component/using
          (map->CSSWatcher {:css-dirs css-dirs})
          [:figwheel-server :log-writer :sync-executor])])))))

(defn add-compiler-env [build]
  (let [build-options (or (:build-options build)
                          (:compiler build))]
    (assoc build
           :build-options build-options
           :compiler-env (cljs.env/default-compiler-env build-options))))

(defn prep-all-options [{:keys [figwheel-options all-builds build-ids]}]
  {:figwheel-options (config/prep-options figwheel-options)
   :all-builds (map add-compiler-env (config/prep-builds all-builds))
   :build-ids (map name build-ids)})

(defn create-figwheel-system [options]
  (create-figwheel-system* (prep-all-options options)))


;; TODO just for dev
(def temp-config
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      :nrepl-port 7888}
   :build-ids  ["example"]
   :all-builds (get-project-builds)})

;; TODO just for dev
#_(def system
  (atom
   (create-figwheel-system* temp-config)))

;; TODO just for dev
#_(count (:builds @system))
#_(keys @system)

;; TODO just for dev
#_(defn start []
  (swap! system component/start))
;; TODO just for dev
#_(defn stop []
  (swap! system component/stop))
;; TODO just for dev
#_(defn reload []
  (swap! system component/stop)
  (require 'figwheel-sidecar.system :reload)
  (swap! system component/start))

#_(reload)


;; figwheel system

(def id->key #(str "autobuild-" (name %)))

(defn key->id [build-key]
  (->> (string/split build-key #"-")
    rest
    (string/join "-")))

(def build-config->key (comp id->key :id))

(defn build-key? [k]
  (.startsWith (name k) "autobuild-"))

(defn valid-key? [system key]
  (if-let [key (get @system (name key))]
    true
    (do
      (println "Figwheel: invalid build id -" (key->id key))
      false)))

(defn all-build-keys [system]
  (set
   (doall
    (filter build-key? (keys @system)))))

(defn ids-or-all-build-keys [system ids]
  (set
   (doall
    (filter (partial valid-key? system)
            (if (not-empty ids)
              (map id->key ids)
              (all-build-keys system))))))

(defn watchers-running [system]
  (set
   (doall
    (filter (fn [key] (get-in @system [key :file-watcher]))
            (all-build-keys system)))))

;; figwheel system control functions

(defn build-diff
  "Makes sure that the autobuilds in the system match the build-ids provided" 
  [system ids]
  (let [ids (set (map id->key ids))
        build-keys (all-build-keys system)]
    {:builds-to-add (map key->id (difference ids build-keys))
     :builds-to-remove (map key->id (difference build-keys ids))}))

(defn system-remove
  "Remove the ids from the system"
  [system ids]
  (let [kys (map id->key ids)]
    (swap! system component/stop-system kys)
    (swap! system #(reduce dissoc % kys))))

(defn patch-system-builds [system ids]
  (let [{:keys [builds-to-remove builds-to-add]} (build-diff system ids)]
    (system-remove system builds-to-remove)
    (doseq [id builds-to-add]
      (when-let [build-config (get (:builds @system) id)]
        (swap! system assoc (id->key id)
               (component/using
                (map->CLJSWatcher {:build-config build-config})
                [:figwheel-server :log-writer :sync-executor]))))))

;; this will tear down the system and start it back up
;; where it left off
(defn stop-and-start-watchers [system build-ids thunk]
  (let [ids (set (mapv key->id (ids-or-all-build-keys system build-ids)))
        all-present-build-ids (set (mapv key->id (all-build-keys system)))
        builds-keep-running (difference all-present-build-ids ids)
        running-build-keys (watchers-running system)]
    (patch-system-builds system (vec builds-keep-running))
    (thunk) 
    (patch-system-builds system ids)
    (swap! system component/start-system running-build-keys))
  nil)

;; TODO ensure that repl printing works

(defn stop-autobuild [system ids]
  (repl-println "Figwheel: Stoping autobuild")
  (swap! system component/stop-system (ids-or-all-build-keys system ids))
  nil)

(defn switch-to-build [system ids]
  (when (not-empty ids)
    (patch-system-builds system ids))
  (swap! system component/start-system (ids-or-all-build-keys system ids))
  nil)

(defn start-autobuild [system ids]
  (repl-println "Figwheel: Starting autobuild")
  (let [current-ids (set (mapv key->id (all-build-keys system)))
        total-ids   (union current-ids (set ids))]
    (switch-to-build system total-ids))
  nil)

(defn clear-compiler-env-for-build-id! [system build-id]
  (swap! system update-in [:builds build-id] add-compiler-env))

#_ (clear-compiler-env-for-build-id! system "example")

(defn clean-build [system id]
  (let [{:keys [builds]} @system]
    (when-let [{:keys [build-options]} (get builds (name id))]
      (repl-println "Figwheel: Cleaning build -" id)
      (clean-cljs-build* build-options)
      (clear-compiler-env-for-build-id! system id)))
  nil)

;; this is going to require a stop and start of the ids
;; and clearing the compiler-env for these builds
(defn clean-builds [system ids]
  (let [ids (map key->id (ids-or-all-build-keys system ids))]
    (stop-and-start-watchers
     system ids
     (fn []
       (doseq [id ids]
         (clean-build system id)))))
  nil)

(defn build-once* [system id]
  (when-let [build-config (get (:builds @system) (name id))]
    (repl-println "Figwheel: Building once -" (name id))
    ((:cljs-build-fn @system)
     (assoc @system :build-config build-config ))))

(defn build-once [system ids]
  (doseq [ky (ids-or-all-build-keys system ids)]
    (build-once* system (key->id ky)))
  nil)

(defn reset-autobuild [system]
  (clean-builds system nil)
  nil)

(defn reload-config [system]
  (let [ids (map key->id (all-build-keys system))]
    (stop-and-start-watchers
     system ids
     (fn []
       (repl-println "Figwheel: Reloading build config information")
       (doseq [id ids]
         (clean-build system id))
       (swap! system assoc :builds (get-project-builds)))))
  nil)

(defn fig-status [system]
    (let [connection-count (get-in @system [:figwheel-server :connection-count])
          watched-builds   (map key->id (watchers-running system))]
     (repl-println "Figwheel System Status")
     (repl-println "----------------------------------------------------")
     (when (not-empty watched-builds)
       (repl-println "Watching builds:" watched-builds))
     (repl-println "Client Connections")
     (when connection-count
       (doseq [[id v] @connection-count]
         (repl-println "\t" (str (if (nil? id) "any-build" id) ":")
                  v (str "connection" (if (= 1 v) "" "s")))))
     (repl-println "----------------------------------------------------"))
    nil)

;; repl-launching
;; TODO not used
#_(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true)
                     opts)
         figwheel-repl-env (frepl/repl-env figwheel-server build)]
     (cljs.repl/repl* figwheel-repl-env (assoc opts :compiler-env (:compiler-env build))))))

(defn build-figwheel-special-fns [system]
  {'start-autobuild (frepl/make-special-fn (partial start-autobuild system))
   'stop-autobuild  (frepl/make-special-fn (partial stop-autobuild system))
   'switch-to-build (frepl/make-special-fn (partial switch-to-build system))
   'clean-builds    (frepl/make-special-fn (partial clean-builds system)) 
   'build-once      (frepl/make-special-fn (partial build-once system))
   'reset-autobuild (frepl/make-special-fn (fn [_] (reset-autobuild system)))
   'reload-config   (frepl/make-special-fn (fn [_] (reload-config system)))
   'fig-status      (frepl/make-special-fn (fn [_] (fig-status system)))})

(defn start-figwheel-repl [system build repl-options]
  (let [{:keys [figwheel-server build-ids]} @system]
    ;; TODO should this be conditional on not running?
    ;; (start-autobuild system build-ids)
    ;; (newline)
    (print "Launching ClojureScript REPL")
    (when-let [id (:id build)] (println " for build:" id))
    (println (frepl/repl-function-docs))
    (println "Prompt will show when figwheel connects to your application")
    (frepl/repl
     build
     figwheel-server
     (update-in repl-options [:special-fns]
                merge
                (build-figwheel-special-fns system)))))

;; choosing which build for the repl to focus on is a bit tricky
;; and we are not considering which builds are connected
;; I think that would just add confusion

(defn choose-repl-build-id [system]
  (let [focused-build-ids (map key->id (all-build-keys system))]
    (if (not-empty focused-build-ids)
      (first focused-build-ids)
      (let [{:keys [build-ids all-builds]} @system]
        (:id (first (config/narrow-builds* all-builds build-ids)))))))

(defn choose-repl-build [system build-id]
  (let [{:keys [builds]} @system]
    (or (and build-id
             (when-let [build (get builds build-id)]
               (and (config/optimizations-none? build)
                    build)))
        (get builds (choose-repl-build-id system)))))

(defn figwheel-cljs-repl
  ([system] (figwheel-cljs-repl system {}))
  ([system {:keys [build-id repl-options]}]
   (when-let [build (choose-repl-build system build-id)]
     (start-figwheel-repl system build repl-options))))

(defn build-switching-cljs-repl
  ([system] (build-switching-cljs-repl system nil))
  ([system start-build-id]
   (loop [build-id start-build-id]
     (figwheel-cljs-repl system build-id)
     (let [{:keys [builds]} @system]
       (when-let [chosen-build-id (frepl/get-build-choice
                                   (keep :id (filter config/optimizations-none? (vals builds))))]
         (recur chosen-build-id))))))

;; figwheel starting and stopping helpers

(defn start-figwheel!
  [{:keys [figwheel-options all-builds build-ids] :as options}]
  (let [system (create-figwheel-system options)]
    (component/start system)))

(defn start-figwheel-and-cljs-repl! [autobuild-options]
  (let [system-atom (atom (start-figwheel! autobuild-options))]
    (build-switching-cljs-repl system-atom)
    system-atom))

(defn stop-figwheel! [system]
  (component/stop system))

;; this is used from the lein plugin
;; it blocks if the repl isn't started
;; todo rename or inline
(defn run-autobuilder [{:keys [figwheel-options all-builds build-ids] :as options}]
  (if (false? (:repl figwheel-options))
    (do
      (start-figwheel! options)
      (loop [] (Thread/sleep 30000) (recur)))
    (start-figwheel-and-cljs-repl! options)))
