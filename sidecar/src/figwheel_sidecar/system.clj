(ns figwheel-sidecar.system
  (:require
   [figwheel-sidecar.utils :as utils]   

   [figwheel-sidecar.repl :refer [repl-println] :as frepl]   
   [figwheel-sidecar.config :as config]

   [figwheel-sidecar.components.nrepl-server     :refer [nrepl-server-component]]
   [figwheel-sidecar.components.css-watcher      :refer [css-watcher]]
   [figwheel-sidecar.components.cljs-autobuild   :as autobuild :refer [cljs-autobuild]]
   [figwheel-sidecar.components.figwheel-server  :refer [figwheel-server]]      
   
   [com.stuartsierra.component :as component]

   [cljs.env :as env]

   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.set :refer [difference union]]
   [clojure.string :as string]))

(defn add-compiler-env [build]
  (let [build-options (or (:build-options build)
                          (:compiler build))]
    (assoc build
           :build-options build-options
           :compiler-env (cljs.env/default-compiler-env build-options))))

;; TODO does this belong here?
(defn get-project-builds []
  (into (array-map)
        (map
         (fn [x]
           [(:id x)
            (add-compiler-env x)])
         (frepl/get-project-cljs-builds))))

;; TODO
(comment

  ;; secondary

  make sure the prep-builds is idempotent
  
  figwheel-server level :reload-clj and :reload-cljc config flags
  
  example html reload)

(declare build-config->key)


;; TODO still wrangling config in a peicemeal fashion
;; better to make this explicit

(defn create-figwheel-system* [{:keys [figwheel-options all-builds build-ids]}]
  (let [builds-to-start (config/narrow-builds* all-builds build-ids)]
    (apply
     component/system-map
     (concat
      [;; :builds needs to be here for the system control functions
       ;; this needs to be an array map
       :builds all-builds 
       :figwheel-server (figwheel-server figwheel-options)]
      ;; add in all of the starting autobuilds
      (mapcat (fn [build-config]
                [(build-config->key build-config)
                 (component/using
                  (cljs-autobuild build-config)
                  [:figwheel-server])])
              builds-to-start)
      (when-let [css-dirs (:css-dirs figwheel-options)]
        ;; TODO ensure directories exist
        [:css-watcher
         (component/using
          (css-watcher css-dirs)
          [:figwheel-server])])
      (when (:nrepl-port figwheel-options)
        [:nrepl-server
         (nrepl-server-component
          (select-keys figwheel-options [:nrepl-port
                                         :nrepl-host
                                         :nrepl-middleware]))])))))

;; doing final config

(defn log-writer [figwheel-options]
  (let [logfile-path (or (:server-logfile figwheel-options) "figwheel_server.log")]
    (if (false? (:repl figwheel-options))
      *out*
      (io/writer logfile-path :append true))))

(defn cljs-build-fn [{:keys [cljs-build-fn]}]
  (or (utils/require-resolve-handler cljs-build-fn)
      autobuild/figwheel-build))

#_(cljs-build-fn {:cljs-build-fn "figwheel-sidecar.components.cljs-autobuild"})

(defn ensure-array-map [all-builds]
  (into (array-map)
        (map (juxt :id identity)
             (if (map? all-builds) (vals all-builds) all-builds))))

(defn prep-all-options [{:keys [figwheel-options all-builds build-ids]}]
  (let [prepped-fig-options (config/prep-options figwheel-options)
        figwheel-opts (assoc prepped-fig-options
                             :log-writer    (log-writer prepped-fig-options)
                             :cljs-build-fn (cljs-build-fn prepped-fig-options))
        all-builds (map add-compiler-env (config/prep-builds all-builds))]
    {:figwheel-options figwheel-opts
     :all-builds (ensure-array-map all-builds)
     :build-ids (map name build-ids)}))

(defn create-figwheel-system [options]
  (create-figwheel-system* (prep-all-options options)))

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
  (if-let [key (get system (name key))]
    true
    (do
      (println "Figwheel: invalid build id -" (key->id key))
      false)))

(defn all-build-keys [system]
  (set
   (doall
    (filter build-key? (keys system)))))

(defn ids-or-all-build-keys [system ids]
  (set
   (doall
    (filter (partial valid-key? system)
            (if (not-empty ids)
              (map id->key ids)
              (all-build-keys system))))))

(defn watchers-running [system]
  (doall
   (filter (fn [key] (get-in system [key :file-watcher]))
           (all-build-keys system))))

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
  (let [build-keys (map id->key ids)
        system     (component/stop-system system build-keys)]
    (reduce dissoc system build-keys)))

(defn add-autobuilder [system build-id]
  (if-let [build-config (get (:builds system) build-id)]
    (assoc system (id->key build-id)
           (component/using
            (cljs-autobuild build-config)
            [:figwheel-server]))
    system))

(defn patch-system-builds [system ids]
  (let [{:keys [builds-to-remove builds-to-add]} (build-diff system ids)
        system (system-remove system builds-to-remove)]
    (reduce add-autobuilder system builds-to-add)))

(defn stop-and-start-watchers [system build-ids thunk]
  (let [build-keys             (ids-or-all-build-keys system build-ids)
        all-present-build-keys (all-build-keys system)
        build-keys-keep        (difference all-present-build-keys build-keys)
        running-build-keys     (watchers-running system)]
    (-> system
        (patch-system-builds (mapv key->id build-keys-keep))
        thunk
        (patch-system-builds (mapv key->id build-keys))
        (component/start-system running-build-keys))))

(defn stop-autobuild [system ids]
  (repl-println "Figwheel: Stoping autobuild")
  (component/stop-system system (ids-or-all-build-keys system ids)))

(defn switch-to-build [system ids]
  (let [system (if (not-empty ids)
                 (patch-system-builds system ids)
                 system)]
    (component/start-system system (ids-or-all-build-keys system ids))))

(defn start-autobuild [system ids]
  (repl-println "Figwheel: Starting autobuild")
  (let [current-ids (set (mapv key->id (all-build-keys system)))
        total-ids   (union current-ids (set ids))]
    (switch-to-build system total-ids)))

(defn clear-compiler-env-for-build-id [system build-id]
  (update-in system [:builds build-id] add-compiler-env))

(defn clean-build [system id]
  (if-let [build-options 
           (get-in system [:builds (name id) :build-options])]
    (do
      (repl-println "Figwheel: Cleaning build -" id)
      (utils/clean-cljs-build* build-options)
      (clear-compiler-env-for-build-id system id))
    system))

;; this is going to require a stop and start of the ids
;; and clearing the compiler-env for these builds
(defn clean-builds [system ids]
  (let [ids (map key->id (ids-or-all-build-keys system ids))]
    (stop-and-start-watchers
     system ids
     #(reduce clean-build % ids))))

;; doesn't change the system
(defn build-once* [system id]
  (when-let [build-config (get (:builds system) (name id))]
    (do
      (repl-println "Figwheel: Building once -" (name id))
      ((:cljs-build-fn system)
       (assoc system :build-config build-config)))
    system))

;; doesn't alter the system
(defn build-once [system ids]
  (doseq [build-ids (mapv key->id
                          (ids-or-all-build-keys system ids))]
    (build-once* system build-ids))
  system)

(defn reset-autobuild [system]
  (clean-builds system nil))

(defn reload-config [system]
  (let [ids (mapv key->id (all-build-keys system))]
    (stop-and-start-watchers
     system ids
     (fn [system]
       (repl-println "Figwheel: Reloading build config information")
       (assoc (doall (reduce clean-build system ids))
        :builds (get-project-builds))))))

;; doesn't alter the system
(defn fig-status [system]
    (let [connection-count (get-in system [:figwheel-server :connection-count])
          watched-builds   (mapv key->id (watchers-running system))]
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
    system)

;; TODO add build-config display

;; going to work with an atom and swap!
;; The commands are side effecting but they are also idempotent
;; the mode of working is going to be one command after another in
;; an interactive repl

(defn system-setter [func system-atom]
  (fn [& args]
    (reset! system-atom (apply func @system-atom args))))

(defn build-figwheel-special-fns [system]
  {'start-autobuild (frepl/make-special-fn (system-setter start-autobuild system))
   'stop-autobuild  (frepl/make-special-fn (system-setter stop-autobuild system))
   'switch-to-build (frepl/make-special-fn (system-setter switch-to-build system))
   'clean-builds    (frepl/make-special-fn (system-setter clean-builds system)) 
   'build-once      (frepl/make-special-fn (system-setter build-once system))
   
   'reset-autobuild (frepl/make-special-fn (system-setter
                                            (fn [sys _] (reset-autobuild sys))
                                            system))
   'reload-config   (frepl/make-special-fn (system-setter
                                            (fn [sys _] (reload-config sys))
                                            system))
   'fig-status      (frepl/make-special-fn (system-setter
                                            (fn [sys _] (fig-status sys))
                                            system))})

(defn start-figwheel-repl [system build repl-options]
  (let [{:keys [figwheel-server build-ids]} @system]
    ;; TODO should I add this this be conditional on not running?
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
      (let [{:keys [build-ids all-builds]} system]
        (:id (first (config/narrow-builds* all-builds build-ids)))))))

(defn choose-repl-build [system build-id]
  (let [{:keys [builds]} system]
    (or (and build-id
             (when-let [build (get builds build-id)]
               (and (config/optimizations-none? build)
                    build)))
        (get builds (choose-repl-build-id system)))))

(defn figwheel-cljs-repl
  ([system] (figwheel-cljs-repl system {}))
  ([system {:keys [build-id repl-options]}]
   (when-let [build (choose-repl-build @system build-id)]
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
