(ns figwheel.main
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [figwheel.repl :as fw-repl]
   [figwheel.core :as fw-core]
   [cljs.main :as cm]
   [cljs.cli :as cli]
   [cljs.build.api :as bapi]
   [hawk.core :as hawk]))

;; TODO
;; builds
;; build config

;; isolate a build by ID for each repl
;; identify a notion of identity(i.e. build-id) at the connection level perhaps a fn

;; better watcher build connection
;; load changed clj files

;; bind connections and verify that it works

;; opening files in editor
;; CSS watching

;; project generation?

;; Watching we want to allow the specification of multiple directories to watch

;; repl-env is assumed to be figwheel

;; ------------------------------------------------------------
;; Watching
;; ------------------------------------------------------------

(def ^:dynamic *watcher* (atom {:watcher nil :watches {}}))

(defn alter-watches [{:keys [watcher watches]} f]
  (when watcher (hawk/stop! watcher))
  (let [watches (f watches)
        watcher (apply hawk/watch! (map vector (vals watches)))]
    {:watcher watcher :watches watches}))

(defn add-watch! [watch-key watch]
  (swap! *watcher* alter-watches #(assoc % watch-key watch)))

(defn remove-watch! [watch-key]
  (swap! *watcher* alter-watches #(dissoc % watch-key)))

(defn reset-watch! []
  (let [{:keys [watcher]} @*watcher*]
    (when watcher (hawk/stop! watcher))
    (reset! *watcher* {})))

(defn throttle [millis f]
  (fn [{:keys [collector] :as ctx} e]
    (let [collector (or collector (atom {}))
          {:keys [collecting? events]} (deref collector)]
      (if collecting?
        (swap! collector update :events (fnil conj []) e)
        (do
          (swap! collector assoc :collecting? true)
          (future (Thread/sleep millis)
                  (let [events (volatile! nil)]
                    (swap! collector
                           #(-> %
                                (assoc :collecting? false)
                                (update :events (fn [evts] (vreset! events evts) nil))))
                    (f (cons e @events))))))
      (assoc ctx :collector collector))))

#_ (reset-watch!)

(defn- watch-opt
  [cfg path]
  (when-not (.exists (io/file path))
    (if (or (string/starts-with? path "-")
            (string/blank? path))
      (throw
        (ex-info
          (str "Missing watch path")
          {:cljs.main/error :invalid-arg}))
      (throw
        (ex-info
          (str "Watch path " path " does not exist")
          {:cljs.main/error :invalid-arg}))))
  (update-in cfg [:options :watch] (fnil conj []) path))

(defn auto-load-opt [cfg bl]
  (assoc-in cfg [::options-meta :auto-load] (not= bl "false")))

(def figwheel-commands
  {:init {
          ["-w" "--watch"]
          {:group :cljs.cli/compile :fn watch-opt
           :arg "path"
           :doc "Continuously build, only effective with the --compile main option"}
          ["-al" "--auto-load"]
          {:group :cljs.cli/compile :fn auto-load-opt
           :arg "bool"
           :doc "Use Figwheel to auto reload. Defaults to true."}}})

(alter-var-root #'cli/default-commands cli/add-commands
                figwheel-commands)

;; ------------------------------------------------------------
;; CLJS Main Argument manipulation
;; ------------------------------------------------------------

(def test-args ["-co" "{:aot-cache false :asset-path \"out\" :preloads [figwheel.repl.preload figwheel.core] :closure-defines {figwheel.repl/connect-url \"ws://localhost:[[client-port]]/figwheel-connect\"}}" #_"-re" #_"figwheel" "--port" "9501" "--watch" "src" "--watch" "wow" "-d" "target/public/out" "-o" "target/public/out/mainer.js" "-e" "(figwheel.core/start-from-repl)"  "-c" "exproj.core" "-r"])

(def canonical-arg
  (->> cli/default-commands
       ((juxt :init :main))
       (apply merge)
       keys
       (filter #(= 2 (count %)))
       (map (comp vec reverse))
       (into {})))

(defn canonicalize [args]
  (map (fn [[k v :as arg]]
         (if-let [ck  (canonical-arg k)]
           (if v [ck v] [ck])
           arg))
       (partition-all 2 args)))

(def arg->coerce
  {"-co" read-string
   "--compile-opts" read-string})

(def arg->coerce-str
  {"-co" pr-str
   "--compile-opts" pr-str})

(defn coerce [f pairs]
  (mapv (fn [[k v]]
          (if-let [f (f k)]
            [k (f v)]
            [k v]))
        pairs))

;; TODO have to handle order better
(defn args->map [args]
  (let [arged (map vec (canonicalize args))
        grouped (into {}
                      (map (fn [[k v]]
                             [k (if (> (count v) 1)
                                  (vary-meta (mapv second v) assoc ::multi true)
                                  (-> v first second))]))
                      (group-by first arged))]
    (into {} (coerce arg->coerce grouped))))

(defn map->args [m]
  (filter some?
          (apply concat
                 (reduce (fn [a [k v]]
                           (concat a (if (and (vector? v) #_(-> v meta ::multi))
                                       (map vector (repeat k) v)
                                       [[k v]])))
                         []
                         (coerce arg->coerce-str m)))))

(defn update-compiler-options [arg-map f]
  (let [opt-name (or (first (filter arg-map ["-co" "--compile-opts"]))
                     "-co")]
    (update arg-map opt-name f)))

(defn add-preload [arg-map preload]
  (update-compiler-options arg-map
   (fn [opts] (update opts :preloads
                      #(-> (vec %)
                           (conj preload)
                           distinct
                           vec)))))

(defn add-to-multi [arg-map k v]
  (update arg-map k (fn [e]
                      (vec
                       (distinct
                        (if (string? e)
                          [e v]
                          (conj (vec e) v)))))))

(defn default-repl-env [arg-map]
  (if (not (or (get arg-map "-re")
               (get arg-map "--repl-env")))
    (-> arg-map
        (assoc "-re" "figwheel")
        (add-to-multi "-e" "(figwheel.core/start-from-repl)")
        (add-preload 'figwheel.repl.preload)
        (add-preload 'figwheel.core))
    arg-map))

;; TODO when do we add REPL preloads and figwheel preloads??
;; THERE is a lot of work to do here to setup the compiler options
;; the default output-dir and output-to

(defn file-suffix [file]
  (last (string/split (.getName (io/file file)) #"\.")))

(defn suffix-filter [suffixes]
  (fn [_ {:keys [file]}]
    (and file
         (.isFile file)
         (not (.isHidden file))
         (suffixes (file-suffix file))
         (not (#{\. \#} (first (.getName file)))))))

#_((suffix-filter #{"clj"}) nil {:file (io/file "project.clj")})

(defn watch [inputs opts cenv]
  (prn "Called Watch!!!")
  (add-watch! (-> opts meta :build-id (or :dev))
              {:paths (if (coll? inputs) inputs [inputs])
               :filter (suffix-filter #{"cljs" "clj" "cljc"})
               :handler (throttle
                         50
                         (fn [evts]
                           (let [inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                             (figwheel.core/build inputs opts cenv))))}))

(defn setup-args [args]
  (let [norm #(cljs.main/normalize (cli/normalize cli/default-commands %))]
    (-> args
        norm
        args->map
        default-repl-env
        map->args
        norm)))

(setup-args test-args)
#_(map->args (args->map test-args))

(defn -main [& args]
  (let [args (setup-args args)]
    (with-redefs [bapi/watch watch]
      (prn args)
      #_(cljs.main/-main args))))
