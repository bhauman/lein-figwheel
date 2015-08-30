(ns figwheel-sidecar.builder-api
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as frepl]   
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.auto-builder :as fauto]
   [figwheel-sidecar.repl :as frepl]
   [clojure.java.io :as io]
   [clojure.core.async :refer [go-loop]]
   [com.stuartsierra.component :as component]
   [cljs.build.api :as bapi]
   [cljs.env :as env]
   [clojurescript-build.core :as cbuild]
   [cljs.closure]
   [clojure.set :refer [difference]]
   [clojure.string :as string])
  (:import
   [java.nio.file Path Paths Files StandardWatchEventKinds WatchKey
    WatchEvent FileVisitor FileVisitResult]
   [com.sun.nio.file SensitivityWatchEventModifier]
   [java.util.concurrent TimeUnit]))

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

(defn get-project-builds []
  (into (array-map)
        (map
         (fn [x]
           [(:id x)
            (assoc x
                   :compiler-env (env/default-compiler-env (:build-options x)))])
         (frepl/get-project-cljs-builds))))

(def dev-build-config (second (first (get-project-builds))))

(keys dev-build-config)

(defrecord FigwheelServer []
  component/Lifecycle
  (start [this]
    (if-not (:http-server this)
      (do
        (println "Figwheel: Starting Server")
        (map->FigwheelServer (fig/start-server this)))
      this))
  (stop [this]
    (when (:http-server this)
      (println "Figwheel: Stopping Server")
      (fig/stop-server this))
    (dissoc this :http-server)))

;; would be nice to have this throttled so it would collect changes
;; perhaps waiting for the callback to finish
(defn watcher [source-paths callback]
  (let [paths (map #(Paths/get (.toURI (io/file %))) source-paths)
        path (first paths)
        fs   (.getFileSystem path)
        srvc (.newWatchService fs)
        quit (atom false)
        millis TimeUnit/MILLISECONDS]
    (letfn [(watch-all [root]
              (Files/walkFileTree root
                                  (reify
                                    FileVisitor
                                    (preVisitDirectory [_ dir _]
                                      (let [^Path dir dir]
                                        (. dir
                                           (register srvc
                                                     (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                                                  StandardWatchEventKinds/ENTRY_DELETE
                                                                  StandardWatchEventKinds/ENTRY_MODIFY])
                                                     (into-array [SensitivityWatchEventModifier/HIGH]))))
                                      FileVisitResult/CONTINUE)
                                    (postVisitDirectory [_ dir exc]
                                      FileVisitResult/CONTINUE)
                                    (visitFile [_ file attrs]
                                      FileVisitResult/CONTINUE)
                                    (visitFileFailed [_ file exc]
                                      FileVisitResult/CONTINUE))))]
      #_(callback [:initialize])
      (doseq [path paths]
        (watch-all path))
      (go-loop [key nil]
        (when (and (or (nil? quit) (not @quit))
                   (or (nil? key) (. ^WatchKey key reset)))
          (let [key     (. srvc (poll 300 millis))]
            (when key
              
              (let [files (filter
                           (fn [x]
                             (let [f (io/file x)]
                               (and (.exists f)
                                    (not (.isDirectory f)))))
                           (mapv
                            (fn [^WatchEvent e]
                              (str (.watchable key) java.io.File/separator  (.. e context)))
                            (seq (.pollEvents key))))]
                (when-not (empty? files)
                  (callback files))))
            (recur key)))))
    quit))

(defn notify-change-helper [{:keys [figwheel-server build-config]} files]
  (let [changed-ns (set (keep fig/get-ns-from-source-file-path (filter #(or (.endsWith % ".cljs")
                                                                            (.endsWith % ".cljc")) files)))]
    (when-not (empty? changed-ns)
      (fig/notify-cljs-ns-changes
       (merge figwheel-server (select-keys (:build-options build-config)
                                           [:output-dir :output-to]))
       changed-ns))))

(defn warning-message-handler [callback]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (callback (cljs.analyzer/message env s))))))

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (CompilableSourcePaths. (:source-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

(defn figwheel-connection-build [build-fn]
  (fn [{:keys [figwheel-server build-config] :as build-state}]
    (build-fn
     (assoc build-state
            :build-config (fauto/add-connect-script! figwheel-server build-config)))
    (fauto/append-connection-init! build-config)))

(defn figwheel-notifications-build [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              (conj cljs.analyzer/*cljs-warning-handlers*
                    (warning-message-handler (fn [warning]
                                               (fig/compile-warning-occured
                                                (fauto/merge-build-into-server-state
                                                 figwheel-server build-config)
                                                warning))))]
      (try
        (build-fn build-state)
        (notify-change-helper watcher changed-files)
        (catch Throwable e
          (fauto/handle-exceptions figwheel-server (assoc build-config :exception e)))))))

(def figwheel-build (-> cljs-build figwheel-connection-build figwheel-notifications-build))


(defn build-handler [{:keys [figwheel-server build-config] :as watcher}
                     files]
  (prn files)
  (figwheel-build (assoc watcher :changed-files files)))

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat libs
            (let [files (mapv :file foreign-libs)]
              (when-not (empty? files) files))
            source-paths)))

(defrecord Watcher [build-config figwheel-server]
  component/Lifecycle
  (start [this]
    (if-not (:file-watcher this)
      (do
        (println "Figwheel: Watching build - " (:id build-config))
        ;; setup
        (fauto/delete-connect-scripts! [build-config])
        (cbuild/clean-build (:build-options build-config))

        ((figwheel-connection-build cljs-build)
         {:build-config build-config :figwheel-server figwheel-server})
        
        (assoc this
               :file-watcher
               (watcher (source-paths-that-affect-build build-config)
                        (fn [files]
                          (#'build-handler this files)))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build - " (:id build-config))
      (reset! (:file-watcher this) true))
    (dissoc this :file-watcher)))

(def id->key #(str "autobuild-" (name %)))
(defn key->id [build-key]
  (->> (string/split build-key #"-")
    rest
    (string/join "-")))

(def build-config->key (comp id->key :id))

(defn build-key? [k]
  (.startsWith (name k) "autobuild-"))

(def system (atom
             (component/system-map
              :builds (get-project-builds)
              :figwheel-server (map->FigwheelServer {})
              (build-config->key dev-build-config)
              (component/using
               (map->Watcher {:build-config dev-build-config})
               [:figwheel-server]))))

(defn build-diff
  "Makes sure that the autobuilds in the system match the builds provided"
  [system ids]
  (let [ids (set (map id->key ids))
        build-keys (set (filter build-key? (keys @system)))]
    {:builds-to-add (map key->id (difference ids build-keys))
     :builds-to-remove (map key->id (difference build-keys ids))}))

(defn system-remove
  "Remove the ids from the system"
  [system ids]
  (let [kys (map id->key ids)]
    (prn kys)
    (swap! system component/stop-system kys)
    (swap! system #(reduce dissoc % kys))))

(defn patch-system-builds [system ids]
  (let [{:keys [builds-to-remove builds-to-add]} (build-diff system ids)]
    (system-remove system builds-to-remove)
    (doseq [id builds-to-add]
      (when-let [build-config (get (:builds @system) id)]
        (swap! system assoc (id->key id)
               (component/using
                (map->Watcher {:build-config build-config})
                [:figwheel-server]))))
    #_(swap! system component/start-system (map id->key builds-to-add))))

(defn all-build-keys [system]
  (filter build-key? (keys @system)))

(defn ids-or-all-build-keys [system ids]
  (if ids
    (map id->key ids)
    (all-build-keys system)))

#_(ids-or-all-build-keys system ["example-admin"])

(defn stop-autobuild [system ids]
  (swap! system component/stop-system (ids-or-all-build-keys system ids)))

(defn start-autobuild [system ids]
  (when ids
    (patch-system-builds system ids))
  (swap! system component/start-system (ids-or-all-build-keys system ids)))

(defn switch-to-build [system ids]
  (when ids
    (start-autobuild system ids)))

(defn clean-build [system id]
  (let [{:keys [builds]} @system]
    (when-let [{:keys [build-options]} (get builds id)]
      (cbuild/clean-build build-options))))

(defn clean-builds [system ids]
  (let [ids (or ids
                (map key->id (filter build-key? (keys @system))))]
    (doseq [id ids]
      (clean-build system id))))

(defn reset-autobuild [system]
  (swap! system component/stop-system (all-build-keys system))
  (clean-builds system nil)
  (swap! system component/start-system (all-build-keys system)))

(defn reload-config [system]
  (swap! system component/stop-system (all-build-keys system))
  (clean-builds system nil)
  (swap! system assoc :builds (get-project-builds))
  (swap! system component/start-system (all-build-keys system)))

(defn build-once* [system id]
  
  )

(defn build-once [system ids]
  

  )


#_ (clean-builds system ["example"])


#_  


#_(stop-autobuild system nil)

#_(start-autobuild system nil)




#_ (in-ns 'figwheel-sidecar.builder-api)

#_ (system-remove system ["example"])

#_(keys @system)





#_(patch-system-builds system ["example" "example-admin"])

#_(build-diff system ["marvelous" "example"])


(defn reload []
  (swap! system component/stop)
  (swap! system component/start))

(defn run-cljs-repl []
  (frepl/repl dev-build-config (:figwheel-server @#'system)))



#_(reload)



#_(build-handler ["/Users/brucehauman/workspace/lein-figwheel/example/src/example/core.cljs"
                  "/Users/brucehauman/workspace/lein-figwheel/example/src/example/style.cljs"])


