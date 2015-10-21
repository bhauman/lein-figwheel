(ns figwheel-sidecar.system
  (:require
   [figwheel-sidecar.utils :as utils]   
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.repl :refer [repl-println] :as frepl]   

   [figwheel-sidecar.components.nrepl-server     :refer [nrepl-server-component]]
   [figwheel-sidecar.components.css-watcher      :refer [css-watcher]]
   [figwheel-sidecar.components.cljs-autobuild   :as autobuild :refer [cljs-autobuild]]
   [figwheel-sidecar.components.figwheel-server  :refer [figwheel-server] :as server]      
   
   [com.stuartsierra.component :as component]

   [cljs.env :as env]

   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.set :refer [difference union]]
   [clojure.string :as string]))

;; TODO
(comment

  ;; secondary

  make sure the prep-builds is idempotent
   
  
  example html reload)

(declare build-config->key)

(defn add-builds [system build-configs]
  (reduce
   (fn [sys build-config]
     (assoc sys
            (build-config->key build-config)
            (component/using
             (cljs-autobuild build-config)
             [:figwheel-server])))
   system
   build-configs))

(defn get-builds-to-start [figwheel-server build-ids]
  (config/narrow-builds* (:builds figwheel-server) build-ids))

(defn add-initial-builds [{:keys [figwheel-server] :as system} build-ids]
  (let [builds-to-start (get-builds-to-start figwheel-server build-ids)]
    (add-builds system builds-to-start)))

(defn add-css-watcher [system css-dirs]
  (if (not-empty css-dirs)
    (assoc system
           :css-watcher
           (component/using
            (css-watcher css-dirs)
            [:figwheel-server]))
    system))

(defn add-nrepl-server [system {:keys [nrepl-port] :as options}]
  (if nrepl-port
    (assoc system :nrepl-server (nrepl-server-component options))
    system))

(defn create-figwheel-system [{:keys [figwheel-options all-builds build-ids] :as options}]
  (-> (component/system-map
       :figwheel-server (figwheel-server figwheel-options all-builds))
      (add-initial-builds (map name build-ids))
      (add-css-watcher  (:css-dirs figwheel-options))
      (add-nrepl-server (select-keys figwheel-options [:nrepl-port
                                                       :nrepl-host
                                                       :nrepl-middleware]))))

;; figwheel system

(def id->key #(str "autobuild-" (name %)))

(defn key->id [build-key]
  (->> (string/split build-key #"-")
    rest
    (string/join "-")))

(def build-config->key (comp id->key :id))

(defn id->build-config [system id]
  (get-in system [:figwheel-server :builds (name id)]))

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
  (if-let [build-config (id->build-config system build-id)]
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
  (update-in system [:figwheel-server :builds build-id] utils/add-compiler-env))

(defn clean-build [system id]
  (if-let [build-options 
           (get-in system [:figwheel-server :builds (name id) :build-options])]
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
  (when-let [build-config
             (id->build-config system (name id))
             #_(get (:builds system) (name id))]
    (do
      (repl-println "Figwheel: Building once -" (name id))
      ;; we are allwing build to be overridden at the system level
      ((or
        (:cljs-build-fn system)
        (get-in system [:figwheel-server :cljs-build-fn])
        autobuild/figwheel-build)
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
       (if-let [new-builds (not-empty (config/get-project-builds))]
         (assoc-in (doall (reduce clean-build system ids))
                   [:figwheel-server :builds]
                   new-builds)
         (do
           (repl-println "No reload config found in project.clj")
           system))))))

;; doesn't alter the system
(defn fig-status [system]
    (let [connection-count (server/connection-data (:figwheel-server system))
          watched-builds   (mapv key->id (watchers-running system))]
     (repl-println "Figwheel System Status")
     (repl-println "----------------------------------------------------")
     (when (not-empty watched-builds)
       (repl-println "Watching builds:" watched-builds))
     (repl-println "Client Connections")
     (when connection-count
       (doseq [[id v] connection-count]
         (repl-println "\t" (str (if (nil? id) "any-build" id) ":")
                  v (str "connection" (if (= 1 v) "" "s")))))
     (repl-println "----------------------------------------------------"))
    system)

;; TODO add build-config display


;; repl interaction


(defn namify [arg]
  (if (seq? arg)
    (when (= 'quote (first arg))
      (str (second arg)))
    (name arg)))

(defn make-special-fn [f]
  (fn self
    ([a b c] (self a b c nil))
    ([_ _ [_ & args] _]
     ;; are we only accepting string ids?
     (f (keep namify args)))))

;; going to work with an atom and swap!
;; The commands are side effecting but they are also idempotent
;; the mode of working is going to be one command after another in
;; an interactive repl

(defn system-setter [func system-atom]
  (fn [& args]
    (reset! system-atom (apply func @system-atom args))))

(defn build-figwheel-special-fns [system]
  {'start-autobuild (make-special-fn (system-setter start-autobuild system))
   'stop-autobuild  (make-special-fn (system-setter stop-autobuild system))
   'switch-to-build (make-special-fn (system-setter switch-to-build system))
   'clean-builds    (make-special-fn (system-setter clean-builds system)) 
   'build-once      (make-special-fn (system-setter build-once system))
   
   'reset-autobuild (make-special-fn (system-setter
                                            (fn [sys _] (reset-autobuild sys))
                                            system))
   'reload-config   (make-special-fn (system-setter
                                            (fn [sys _] (reload-config sys))
                                            system))
   'fig-status      (make-special-fn (system-setter
                                            (fn [sys _] (fig-status sys))
                                            system))})

(def repl-function-docs 
  "Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (reload-config)                 ;; reloads build config and resets autobuild
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (fig-status)                    ;; displays current state of system
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object")

(defn start-figwheel-repl [system build repl-options]
  (let [{:keys [figwheel-server build-ids]} @system]
    ;; TODO should I add this this be conditional on not running?
    ;; (start-autobuild system build-ids)
    ;; (newline)
    (print "Launching ClojureScript REPL")
    (when-let [id (:id build)] (println " for build:" id))
    (println repl-function-docs)
    (println "Prompt will show when Figwheel connects to your application")
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

(defn get-build-choice [choices]
  (let [choices (set (map name choices))]
    (loop []
      (print (str "Choose focus build for CLJS REPL (" (clojure.string/join ", " choices) ") or quit > "))
      (flush)
      (let [res (read-line)]
        (cond
          (nil? res) false
          (choices res) res          
          (= res "quit") false
          (= res "exit") false
          :else
          (do
            (println (str "Error: " res " is not a valid choice"))
            (recur)))))))

(defn choose-repl-build [system build-id]
  (let [builds (get-in system [:figwheel-server :builds])]
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
     (let [builds (get-in @system [:figwheel-server :builds])]
       (when-let [chosen-build-id
                  (get-build-choice
                   (keep :id (filter config/optimizations-none? (vals builds))))]
         (recur chosen-build-id))))))

;; figwheel starting and stopping helpers

(defn start-figwheel!
  [{:keys [figwheel-options all-builds build-ids] :as options}]
  (let [system (create-figwheel-system options)]
    (component/start system)))

(defn start-figwheel-and-cljs-repl! [autobuild-options]
  #_(p/pprint autobuild-options)
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

(defn load-config-run-autobuilder [{:keys [build-ids]}]
  (let [options (-> {} ;; not relying on project in the case of
                    ;; being called from the plugin
                    (config/figwheel-ambient-config build-ids)
                    config/prep-figwheel-config)]
    (run-autobuilder options)))
