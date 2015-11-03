(ns figwheel-sidecar.system
  (:require
   [figwheel-sidecar.utils :as utils]   
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.repl :refer [repl-println] :as frepl]   

   [figwheel-sidecar.components.nrepl-server   :as nrepl-comp]
   [figwheel-sidecar.components.css-watcher     :as css-watch]
   [figwheel-sidecar.components.cljs-autobuild   :as autobuild]
   [figwheel-sidecar.components.figwheel-server :as server]      
   
   [com.stuartsierra.component :as component]

   [cljs.env :as env]

   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.set :refer [difference union]]
   [clojure.string :as string]))

(def fetch-config config/fetch-config)

(defn css-watcher [opts]
  (component/using
   (css-watch/css-watcher opts)
   {:figwheel-server :figwheel-system}))

(def nrep-server-component nrepl-comp/nrepl-server-component)

;; TODO
(comment

  ;; secondary
  
  example html reload)

(declare build-config->key)

(defn add-builds [system build-configs]
  (reduce
   (fn [sys build-config]
     (assoc sys
            (build-config->key build-config)
            (component/using
             (autobuild/cljs-autobuild {:build-config build-config})
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
    (assoc system :css-watcher (css-watcher {:watch-paths css-dirs}))
    system))

(defn add-nrepl-server [system {:keys [nrepl-port] :as options}]
  (if nrepl-port
    (assoc system :nrepl-server (nrepl-comp/nrepl-server-component options))
    system))


;; Initially I really wanted a bunch of top level components that
;; relied on a figwheel server. I wanted this to be simple and
;; intuitive. Like so
(comment
  (component/system-map
   :figwheel-server   (figwheel-server (config/fetch-config))
   :example-autobuild (component/using
                       (cljs-autobuild {:build-id "example" })
                       [:figwheel-server])
   :css-watcher       (component/using
                       (css-watcher {:watch-paths ["resources/public/css"]})
                       [:figwheel-server])))
;; Unfortunately the requirement that the overal system be controlled
;; from the CLJS repl make this difficult and forced invasive
;; requirements on the overall system.  For this reason I have introduced and
;; overall component called FigwheelSystem that is a containing
;; component for a system that contains a figwheel-server and an ever
;; changing set of autobuilds that can be controlled from the repl.
;;
;; The FigwheelSystem will start autobuilding the builds according to
;; the supplied :build-ids, just as figwheel does now.
;;
;; This sets us up for a future that looks like this:

(comment
  (component/system-map
   :figwheel-system (figwheel-system (config/fetch-config))
   :css-watcher     (component/using
                      (css-watcher {:build-id "example" })
                      {:figwheel-system :figwheel-server})
   :cljs-socket-repl (component/using
                      (cljs-socket-repl {:build-id "example"})
                      [:figwheel-system])))

;; Being able to supply a mutable figwheel system that can be
;; controled by a CLJS socket repl is very desirable.
;;
;; That being said if one doesn't want the CLJS repl to be able to
;; control which builds are running etc then one can revert the method
;; described first.
;;
;; Here is our FigwheelSystem it's just a wrapper component that
;; provides the same behavior/protocol as the
;; contained :figwheel-server
;;
;; This allows components in the greater system to send messages to
;; the connected clients.

(defrecord FigwheelSystem [system]
  component/Lifecycle
  (start [this]
    (if-not (:system-running this)
      (do
        (swap! system component/start)
        (assoc this :system-running true))
      this))
  (stop [this]
    (if (:system-running this)
      (do 
        (swap! system component/stop)
        (assoc this :system-running false))
      this))
  server/ChannelServer
  (-send-message [this channel-id msg-data callback]
    (server/-send-message (:figwheel-server @system)
                   channel-id msg-data callback))
  (-connection-data [this]
    (server/-connection-data (:figwheel-server @system))))

(defn figwheel-system [{:keys [build-ids] :as options}]
  (let [system
        (atom
         (-> (component/system-map
              :figwheel-server (server/figwheel-server options))
             (add-initial-builds (map name build-ids))))]
    (map->FigwheelSystem {:system system})))

(defn create-figwheel-system
  "This creates a complete Figwheel only system. It conditionally adds
  a CSS watcher if the configuration contains :css-dirs and
  conditionally adds an nREPL server component if the Figwheel
  configuration contains an :nrepl-port key.

  This takes a figwheel configuration consisting
  of :fighweel-options, :all-builds, and :build-ids.

  If you only have a few components to add to this system then you can
  assoc them onto the created system before you start like so.

  (def my-system 
    (assoc (create-figwheel-system (config/fetch-config))
           :html-reload 
           (component/using 
             (html-reloader {:watch-paths [\"resources/public\"]})
             [:figwheel-system])
           :web-server (my-webserver-component)))"
  [{:keys [figwheel-options all-builds build-ids]
    :as options}]
  (-> (component/system-map
       :figwheel-system (figwheel-system options))
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

;; figwheel system control function helpers

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
    (assoc system
           (id->key build-id)
           (component/using
            (autobuild/cljs-autobuild {:build-config build-config})
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

;; start System Control Functions
;; These commands are intended to be run from a Clojure REPL or to be
;; integrated as special functions in the CLJS REPL
;; These commands alter the figwheel system and manage the running autobuilds

(defn stop-autobuild
  "Stops autobuilding the specific ids or all currently running autobuilds"
  [system ids]
  (repl-println "Figwheel: Stoping autobuild")
  (component/stop-system system (ids-or-all-build-keys system ids)))

(defn switch-to-build
  "Switches autobuilding from the current autobuilds to the given build ids"
  [system ids]
  (let [system (if (not-empty ids)
                 (patch-system-builds system ids)
                 system)]
    (component/start-system system (ids-or-all-build-keys system ids))))

(defn start-autobuild
  "Either starts all the current autobuilds or adds the supplied build
  and starts autobuilding it."
  [system ids]
  (repl-println "Figwheel: Starting autobuild")
  (let [current-ids (set (mapv key->id (all-build-keys system)))
        total-ids   (union current-ids (set ids))]
    (switch-to-build system total-ids)))

(defn clear-compiler-env-for-build-id [system build-id]
  (update-in system [:figwheel-server :builds build-id] config/add-compiler-env))

(defn- clean-build
  "Deletes compiled assets for given id."
  [system id]
  (if-let [build-options 
           (get-in system [:figwheel-server :builds (name id) :build-options])]
    (do
      (repl-println "Figwheel: Cleaning build -" id)
      (utils/clean-cljs-build* build-options)
      (clear-compiler-env-for-build-id system id))
    system))

;; this is going to require a stop and start of the ids
;; and clearing the compiler-env for these builds
(defn clean-builds
  "Deletes the compiled assets for the given ids or cleans all the
  current autobuilds. This command stops and starts the autobuilds."
  [system ids]
  (let [ids (map key->id (ids-or-all-build-keys system ids))]
    (stop-and-start-watchers
     system ids
     #(reduce clean-build % ids))))

;; doesn't change the system
(defn- build-once* [system id]
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
(defn build-once
  "Builds the given ids or the current builds once"
  [system ids]
  (doseq [build-ids (mapv key->id
                          (ids-or-all-build-keys system ids))]
    (build-once* system build-ids))
  system)

(defn reset-autobuild
  "Alias for clean-builds"
  [system]
  (clean-builds system nil))

(defn reload-config
  "Resets the system and reloads the the confgiguration as best it can."
  [system]
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

(defn print-config
  "Prints out the build config for the given ids or all the build configs"
  [system ids]
  (let [ids (or (not-empty ids)
                (keys (get-in system [:figwheel-server :builds])))]
    (doseq [build-confg (map (partial id->build-config system) ids)]
      (p/pprint (dissoc build-confg :compiler-env))))
  system)

;; doesn't alter the system
(defn fig-status
  "Prints out the status of the running figwheel system"
  [system]
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

;; end System Control Functions

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
   'print-config   (make-special-fn (system-setter print-config system))
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
          (print-config [id ...])         ;; prints out build configurations
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

(defn figwheel-cljs-repl* [system {:keys [build-id repl-options]}]
  (when-let [build (choose-repl-build @system build-id)]
     (start-figwheel-repl system build repl-options)))

(defn build-switching-cljs-repl* [system start-build-id]
  (loop [build-id start-build-id]
    (figwheel-cljs-repl* system build-id)
    (let [builds (get-in @system [:figwheel-server :builds])]
      (when-let [chosen-build-id
                 (get-build-choice
                  (keep :id (filter config/optimizations-none? (vals builds))))]
        (recur chosen-build-id)))))

;; takes a FigwheelSystem
(defn figwheel-cljs-repl
  ([figwheel-system]
   (figwheel-cljs-repl figwheel-system {}))
  ([{:keys [system]} repl-options]
   (figwheel-cljs-repl* system repl-options)))

;; takes a FigwheelSystem
(defn build-switching-cljs-repl
  ([figwheel-system] (build-switching-cljs-repl figwheel-system nil))
  ([{:keys [system]} start-build-id]
   (build-switching-cljs-repl* system start-build-id)))

;; figwheel starting and stopping helpers

(defn start-figwheel!
  [{:keys [figwheel-options all-builds build-ids] :as options}]
  (let [system (create-figwheel-system options)]
    (component/start system)))

;; this is a blocking call
(defn start-figwheel-and-cljs-repl! [autobuild-options]
  (let [system (start-figwheel! autobuild-options)]
    (build-switching-cljs-repl (:figwheel-system system))))

(defn stop-figwheel! [system]
  (component/stop system))
