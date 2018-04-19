(ns figwheel.main
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [figwheel.repl :as fw-repl]
   [figwheel.core :as fw-core]
   [cljs.main :as cm]
   [cljs.cli :as cli]
   [cljs.repl]
   [cljs.env]
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
   [cljs.build.api :as bapi]
   [hawk.core :as hawk])
  (:import
   [java.io StringReader]
   [java.net.URI]
   [java.nio.file.Paths]))

;; ------------------------------------------------------------
;; Watching
;; ------------------------------------------------------------

(def ^:dynamic *watcher* (atom {:watcher nil :watches {}}))

(defonce process-unique (subs (str (java.util.UUID/randomUUID)) 0 6))

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
  (when-let [inputs (if (coll? inputs) inputs [inputs])]
    (add-watch! (-> opts meta :build-id (or :dev))
                {:paths inputs
                 :filter (suffix-filter #{"cljs" "clj" "cljc"})
                 :handler (throttle
                           50
                           (fn [evts]
                           (let [inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                             (figwheel.core/build inputs opts cenv))))})))

;; ----------------------------------------------------------------------------
;; Additional cli options
;; ----------------------------------------------------------------------------

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
  (update-in cfg [::config :watch] (fnil conj []) path))

(defn figwheel-opt [cfg bl]
  (assoc-in cfg [::config :figwheel] (not= bl "false")))

(defn get-build [bn]
  (try (read-string (slurp (str bn ".cljs.edn")))
             (catch Throwable t
               (throw
                (ex-info (str "Couldn't read the build file: " (str bn ".cljs.edn"))
                         {:cljs.main/error :invalid-arg})))))

;; TODO util
(defn path-parts [uri]
  (map str (java.nio.file.Paths/get uri)))

;; TODO util
(defn relativized-path-parts [path]
  (let [local-dir-parts (path-parts (java.net.URI/create (str "file:" (System/getProperty "user.dir"))))
        parts (path-parts (java.net.URI/create (str "file:" (.getCanonicalPath (io/file path)))))]
    [local-dir-parts parts]
    (when (= local-dir-parts (take (count local-dir-parts) parts))
      (drop (count local-dir-parts) parts))))

(defn watch-dir-from-ns [main-ns]
  (let [source (bapi/ns->location main-ns)]
    (when-let [f (:uri source)]
      (let [res (relativized-path-parts (.getPath f))
            end-parts (path-parts (java.net.URI/create (str "file:/" (:relative-path source))))]
        (when (= end-parts (take-last (count end-parts) res))
          (str (apply io/file (drop-last (count end-parts) res))))))))

(defn watch-dirs-from-build [{:keys [main] :as build-options}]
  (let [watch-dirs (-> build-options meta :watch-dirs)
        main-ns-dir (and main (watch-dir-from-ns (symbol main)))]
    (not-empty (filter #(.exists (io/file %)) (cons main-ns-dir watch-dirs)))))

(defn build-opt [cfg bn]
  (when-not (.exists (io/file (str bn ".cljs.edn")))
    (if (or (string/starts-with? bn "-")
            (string/blank? bn))
      (throw
        (ex-info
          (str "Missing build name")
          {:cljs.main/error :invalid-arg}))
      (throw
        (ex-info
          (str "Build " (str bn ".cljs.edn") " does not exist")
          {:cljs.main/error :invalid-arg}))))
  (let [options (get-build bn)
        watch-dirs (watch-dirs-from-build options)]
    (-> cfg
        (update :options merge options)
        (update-in [::config :watch-dirs] (comp distinct concat) watch-dirs)
        ;; TODO handle merge smarter
        (update ::config merge (dissoc (meta options) :watch-dirs))
        (assoc-in  [::build :id] bn))))

(defn build-once-opt [cfg bn]
  (let [cfg (build-opt cfg bn)]
    (assoc-in cfg [::config :mode] :build-once)))

(def figwheel-commands
  {:init {["-w" "--watch"]
          {:group :cljs.cli/compile :fn watch-opt
           :arg "path"
           :doc "Continuously build, only effective with the --compile main option"}
          ["-fw" "--figwheel"]
          {:group :cljs.cli/compile :fn figwheel-opt
           :arg "bool"
           :doc (str "Use Figwheel to auto reload and report compile info.\n"
                     "Only takes effect when watching is happening and the\n"
                     "optimizations level is :none or nil.\n"
                     "Defaults to true.")}
          ["-b" "--build"]
          {:group :cljs.cli/compile :fn build-opt
           :arg "build-name"
           :doc (str "The name of a build config to build.")}
          ["-bo" "--build-once"]
          {:group :cljs.cli/compile :fn build-once-opt
           :arg "build-name"
           :doc (str "The name of a build config to build once.")}}})

(alter-var-root #'cli/default-commands cli/add-commands
                figwheel-commands)

;; ----------------------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------------------

(def default-output-dir (.getPath (io/file "target" "public" "cljs-out")))
(def default-output-to  (.getPath (io/file "target" "public" "cljs-out" "main.js")))

(let [rgx (if
            (= (System/getProperty "file.separator") "\\")
            (java.util.regex.Pattern/compile "\\\\")
            (java.util.regex.Pattern/compile (System/getProperty "file.separator")))]
  (defn file-path-parts [path]
    (string/split (str path) rgx)))

(defn figure-default-asset-path [{:keys [figwheel-options options] :as cfg}]
  (let [{:keys [output-dir]} options]
    ;; if you have configured your static resources you are on your own
    (when-not (contains? (:ring-stack-options figwheel-options) :static)
      (let [parts (relativized-path-parts (or output-dir default-output-dir))]
        (when-let [asset-path
                   (->> parts
                        (split-with (complement #{"public"}))
                        last
                        rest
                        not-empty)]
          (str (apply io/file asset-path)))))))

(defn- config-update-watch-dirs [{:keys [options ::config] :as cfg}]
  ;; remember we have to fix this for the repl-opt fn as well
  ;; so that it understands multiple watch directories
  (update-in cfg [::config :watch-dirs]
            #(not-empty
              (distinct
               (cond-> %
                 (:watch options) (conj (:watch options)))))))

(defn- config-repl-serve? [{:keys [ns args] :as cfg}]
  (let [rfs      #{"-r" "--repl"}
        sfs      #{"-s" "--serve"}
        mode (get-in cfg [::config :mode])]
    (if mode
      cfg
      (cond-> cfg
      (boolean (or (rfs ns) (rfs (first args)))) (assoc-in [::config :mode] :repl)
      (boolean (or (sfs ns) (sfs (first args)))) (assoc-in [::config :mode] :serve)))))

(defn- config-main-ns [{:keys [ns options] :as cfg}]
  (let [main-ns (if (and ns (not (#{"-r" "--repl" "-s" "--serve"} ns)))
                  (symbol ns)
                  (:main options))]
    (cond-> cfg
      main-ns (assoc :ns main-ns)
      main-ns (assoc-in [:options :main] main-ns))))

(defn figwheel-mode? [config options]
  (and (:figwheel config true)
       (not-empty (:watch-dirs config))
       (= :none (:optimizations options :none))))

(defn- config-figwheel-mode? [{:keys [::config options] :as cfg}]
  (cond-> cfg
    ;; check for a main??
    (figwheel-mode? config options)
    (->
     (#'cljs.cli/eval-opt "(figwheel.core/start-from-repl)")
     (update-in [:options :preloads]
                (fn [p]
                  (vec (distinct
                        (concat p '[figwheel.repl.preload figwheel.core]))))))))

(defn- config-default-dirs [{:keys [options] :as cfg}]
  (cond-> cfg
    (nil? (:output-to options))
    (assoc-in [:options :output-to] default-output-to)
    (nil? (:output-dir options))
    (assoc-in [:options :output-dir] default-output-dir)))

(defn- config-default-asset-path [{:keys [options] :as cfg}]
  (cond-> cfg
    (nil? (:asset-path options))
    (assoc-in [:options :asset-path] (figure-default-asset-path cfg))))

(defn- config-default-aot-cache-false [{:keys [options] :as cfg}]
  (cond-> cfg
    (not (contains? options :aot-cache))
    (assoc-in [:options :aot-cache] false)))

;; TODO util
(defn require? [symbol]
  (try
    (require symbol)
    true
    (catch Exception e
      #_(println (.getMessage e))
      #_(.printStackTrace e)
      false)))

;; TODO util
(defn require-resolve-handler [handler]
  (when handler
    (if (fn? handler)
      handler
      (let [h (symbol handler)]
        (when-let [ns (namespace h)]
          (when (require? (symbol ns))
            (when-let [handler-var (resolve h)]
              handler-var)))))))

(defn process-figwheel-main-edn [{:keys [ring-handler] :as main-edn}]
  (let [handler (and ring-handler (require-resolve-handler ring-handler))]
    (when (and ring-handler (not handler))
      (throw (ex-info "Unable to find :ring-handler" {:ring-handler ring-handler})))
    (cond-> main-edn
      handler (assoc :ring-handler handler))))

(defn- config-figwheel-main-edn [cfg]
  (if-not (.exists (io/file "figwheel-main.edn"))
    cfg
    (let [config-edn (process-figwheel-main-edn
                      (try (read-string (slurp (io/file "figwheel-main.edn")))
                           (catch Throwable t
                             (throw (ex-info "Problem reading figwheel-main.edn" )))))]
      (-> cfg
          (update :repl-env-options
                  merge (select-keys config-edn
                                     [:ring-server
                                      :ring-server-options
                                      :ring-stack
                                      :ring-stack-options
                                      :ring-handler]))
          (update ::config merge config-edn)))))

(defn config-clean [cfg]
  (update cfg :options dissoc :watch))

#_(defn config-repl-connection [cfg]
  (if-not (or (::repl? cfg) (::figwheel-mode? cfg))

    )
  ;; modify of create new connect-url and add
  )

(defn update-config [cfg]
  (->> cfg
       config-figwheel-main-edn
       config-update-watch-dirs
       config-repl-serve?
       config-figwheel-mode?
       config-main-ns
       config-default-dirs
       config-default-asset-path
       config-default-aot-cache-false
       config-clean))

(defn get-repl-options [{:keys [options args inits repl-options] :as cfg}]
  (assoc (merge (dissoc options :main)
                repl-options)
         :inits
         (into
          [{:type :init-forms
            :forms (when-not (empty? args)
                     [`(set! *command-line-args* (list ~@args))])}]
          inits)))

;; ----------------------------------------------------------------------------
;; Main action
;; ----------------------------------------------------------------------------

(defn build [{:keys [watch-dirs mode] :as config} options cenv]
  (let [source (when (and (= :none (:optimizations options :none)) (:main options))
                 (:uri (bapi/ns->location (symbol (:main options)))))]
    (if-let [paths (and (not= mode :build-once) (not-empty watch-dirs))]
      (do
        (bapi/build (apply bapi/inputs paths) options cenv)
        (watch paths options cenv))
      (cond
        source
        (bapi/build source options cenv)
        ;; TODO need compile paths
        (not-empty watch-dirs)
        (bapi/build (apply bapi/inputs watch-dirs) options cenv)))))

(defn repl [repl-env-fn repl-env-options repl-options]
  (cljs.repl/repl* (->> (select-keys repl-options [:output-to :output-dir])
                        (merge repl-env-options)
                        (mapcat identity)
                        (apply repl-env-fn))
                   repl-options))

(defn serve [repl-env-fn repl-env-options repl-options & [eval-str]]
  (let [re-opts (merge repl-env-options
                       (select-keys repl-options [:output-dir :output-to]))
        renv (apply repl-env-fn (mapcat identity re-opts))]
    (binding [cljs.repl/*repl-env* renv]
      (cljs.repl/-setup renv repl-options)
      ;; TODO is this better than a direct call?
      ;; I don't think so
      (when eval-str
        (cljs.repl/evaluate-form renv
                                 (assoc (ana/empty-env)
                                        :ns (ana/get-namespace ana/*cljs-ns*))
                                 "<cljs repl>"
                                 ;; todo allow opts to be added here
                                 (first (ana-api/forms-seq (StringReader. eval-str)))))
      (when-let [server @(:server renv)]
        (.join server)))))

;; TODO make this into a figwheel-start that
;; takes the correct config
;; and make an adapter function that produces the correct args for this fn
(defn default-compile [repl-env cfg]
  (let [{:keys [ns args options repl-env-options ::config] :as cfg} (update-config cfg)
        {:keys [mode pprint-config]} config
        cenv (cljs.env/default-compiler-env)]
    (cljs.env/with-compiler-env cenv
      (if pprint-config
        (do (clojure.pprint/pprint cfg) cfg)
        (let [fw-mode? (figwheel-mode? config options)]
          (build config options cenv)
          (when-not (= mode :build-once)
            (cond
              (= mode :repl)
              (repl repl-env repl-env-options (get-repl-options cfg))
              (or (= mode :serve) fw-mode?)
              (serve repl-env
                     repl-env-options
                     (get-repl-options cfg)
                     (when fw-mode? "(figwheel.core/start-from-repl)")))))))))



;; TODO still searching for an achitecture here
;; we need a better overall plan for config data
;; we need to be aiming towards concrete config for the actions to be taken
;; :repl-env-options :repl-options and compiler :options
;; :watcher options
;; :background builds need their own options


(def server (atom nil))

(def ^:dynamic *config*)

;; we should add a compile directory option

;; build option
;; adds -c option if not available, and no other main options
;; adds -c (:main build options) fail if no -c option and c main is not available
;; when other main options merge with other -co options
;; adds -c option if -r or -s option is present

;; adds -c option
;; and
;; - (:main build options is present
;; - -c option not present and -r or -s option and not other main option

;; else option just adds options

;; interesting case for handling when there is no main function but there is a watch

;; in default compile
;; takes care of default watch and default repl
;; merges build-options with provided options

(defn split-at-opt [opt-set args]
    (split-with (complement opt-set) args))

(defn split-at-main-opt [args]
  (split-at-opt (set (keys (:main-dispatch cli/default-commands))) args))

(defn get-init-opt [opt-set args]
  (->> (split-at-main-opt args)
       first
       (split-at-opt opt-set)
       last
       second))

(defn update-opt [args opt-set f]
  (let [[mpre mpost] (split-at-main-opt args)
        [opt-pre opt-post] (split-at-opt opt-set mpre)]
    (if-not (empty? opt-post)
      (concat opt-pre [(first opt-post)] [(f (second opt-post))]
              (drop 2 opt-post) mpost)
      (concat [(first opt-set) (f nil)] args))))

(def get-build-opt (partial get-init-opt #{"-b" "--build"}))
(def get-build-once-opt (partial get-init-opt #{"-bo" "--build-once"}))
(defn get-main-opt-flag [args] (first (second (split-at-main-opt args))))

(defn handle-build-opt [args]
  (if-let [build-name (or (get-build-opt args) (get-build-once-opt args))]
    (let [build-options (get-build build-name)
          main-flag (get-main-opt-flag args)
          main-ns (:main build-options)]
      (cond
        (#{"-c" "--compile" "-m" "--main" "-h" "--help" "-?"} main-flag)
        args
        main-ns
        (let [[pre post] (split-at-main-opt args)]
          (concat pre ["-c" (str main-ns)] post))
        :else args))
    args))

;; TODO figwheel.core start when not --repl
(defn -main [& args]
  (let [[pre post] (split-with (complement #{"-re" "--repl-env"}) args)
        args (if (empty? post) (concat ["-re" "figwheel"] args) args)
        args (handle-build-opt args)]
    (with-redefs [cljs.cli/default-compile default-compile]
      (apply cljs.main/-main args))))

(def args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
