(ns figwheel.main
  (:require
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
   [cljs.build.api :as bapi]
   [cljs.cli :as cli]
   [cljs.env]
   [cljs.main :as cm]
   [cljs.repl]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [figwheel.core :as fw-core]
   [figwheel.main.watching :as fww]
   [figwheel.main.util :as fw-util]
   [figwheel.repl :as fw-repl])
  (:import
   [java.io StringReader]
   [java.net.InetAddress]
   [java.net.URI]
   [java.net.URLEncoder]
   [java.nio.file.Paths]))

(defonce process-unique (subs (str (java.util.UUID/randomUUID)) 0 6))

#_((suffix-filter #{"clj"}) nil {:file (io/file "project.clj")})

;; config :reload-clj-files false
;; :reload-clj-files {:clj true :cljc false}

(defn watch [inputs opts cenv & [reload-config]]
  (when-let [inputs (if (coll? inputs) inputs [inputs])]
    (fww/add-watch! (-> opts meta :build-id (or :dev))
                {:paths inputs
                 :filter (fww/suffix-filter (into #{"cljs" "js"}
                                              (cond
                                                (coll? (:reload-clj-files reload-config))
                                                (mapv name (:reload-clj-files reload-config))
                                                (false? (:reload-clj-files reload-config)) []
                                                :else ["clj" "cljc"])))
                 :handler (fww/throttle
                           50
                           (bound-fn [evts]
                             (let [files (mapv (comp #(.getCanonicalPath %) :file) evts)
                                   inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                               (try
                                 (when-let [clj-files
                                            (not-empty
                                             (filter
                                              #(or (.endsWith % ".clj")
                                                   (.endsWith % ".cljc"))
                                              files))]
                                   (figwheel.core/reload-clj-files clj-files))
                                 (figwheel.core/build inputs opts cenv files)
                                 (catch Throwable t
                                   #_(clojure.pprint/pprint
                                    (Throwable->map t))
                                   (figwheel.core/notify-on-exception
                                    cljs.env/*compiler* t {})
                                   false)))))})))

(def validate-config!
  (if (try
          (require 'clojure.spec.alpha)
          (require 'expound.alpha)
          (require 'figwheel.main.schema)
          true
          (catch Throwable t false))
    (resolve 'figwheel.main.schema/validate-config!)
    ;; TODO pring informative message about config validation
    (fn [a b])))

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
  (let [fname (str bn ".cljs.edn")
        build (try (read-string (slurp fname))
                   (catch Throwable t
                     ;; TODO use error parsing here to create better error message
                     (throw
                      (ex-info (str "Couldn't read the build file: " fname " : " (.getMessage t))
                               {:cljs.main/error :invalid-arg}
                               t))))]
    (when (meta build)
      (validate-config! (meta build)
                        (str "Configuration error in " fname)))
    build))

(defn watch-dir-from-ns [main-ns]
  (let [source (bapi/ns->location main-ns)]
    (when-let [f (:uri source)]
      (let [res (fw-util/relativized-path-parts (.getPath f))
            end-parts (fw-util/path-parts (:relative-path source))]
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
        (assoc-in [::build-name] bn))))

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

;; ----------------------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------------------

(defn default-output-dir [& [scope]]
  (->> (cond-> ["target" "public" "cljs-out"]
         scope (conj scope))
       (apply io/file)
       (.getPath)))

(defn default-output-to [& [scope]]
  (.getPath (io/file "target" "public" "cljs-out"
                     (cond->> "main.js"
                       scope (str scope "-")))))

(defn figure-default-asset-path [{:keys [figwheel-options options ::build-name] :as cfg}]
  (let [{:keys [output-dir]} options]
    ;; TODO could discover the resource root if there is only one
    ;; or if ONLY static file serving can probably do something with that
    ;; as well
    ;; UNTIL THEN if you have configured your static resources no default asset-path
    (when-not (contains? (:ring-stack-options figwheel-options) :static)
      (let [parts (fw-util/relativized-path-parts (or output-dir (default-output-dir build-name)))]
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

;; TODO this is a no-op right now
(defn prep-client-config [config]
  (let [cl-config (select-keys config [])]
    cl-config))

(defn start-figwheel-code [config]
  (let [client-config (prep-client-config config)]
    (if (not-empty client-config)
      (str "(figwheel.core/start-from-repl " (pr-str client-config)  ")")
      "(figwheel.core/start-from-repl)")))

(defn- config-figwheel-mode? [{:keys [::config options] :as cfg}]
  (cond-> cfg
    ;; check for a main??
    (figwheel-mode? config options)
    (->
     (#'cljs.cli/eval-opt (start-figwheel-code config))
     (update-in [:options :preloads]
                (fn [p]
                  (vec (distinct
                        (concat p '[figwheel.repl.preload figwheel.core]))))))))

(defn- config-default-dirs [{:keys [options ::build-name] :as cfg}]
  (cond-> cfg
    (nil? (:output-to options))
    (assoc-in [:options :output-to] (default-output-to build-name))
    (nil? (:output-dir options))
    (assoc-in [:options :output-dir] (default-output-dir build-name))))

(defn- config-default-asset-path [{:keys [options] :as cfg}]
  (cond-> cfg
    (nil? (:asset-path options))
    (assoc-in [:options :asset-path] (figure-default-asset-path cfg))))

(defn- config-default-aot-cache-false [{:keys [options] :as cfg}]
  (cond-> cfg
    (not (contains? options :aot-cache))
    (assoc-in [:options :aot-cache] false)))

(defn process-figwheel-main-edn [{:keys [ring-handler] :as main-edn}]
  (validate-config! main-edn "Configuration error in figwheel-main.edn")
  (let [handler (and ring-handler (fw-util/require-resolve-var ring-handler))]
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

;; TODO create connection

(let [localhost (promise)]
  ;; this call takes a very long time to complete so lets get in in parallel
  (doto (Thread. #(deliver localhost (java.net.InetAddress/getLocalHost)))
    (.setDaemon true)
    (.start))
  (defn fill-connect-url-template [url host server-port]
    (cond-> url
      (.contains url "[[config-hostname]]")
      (string/replace "[[config-hostname]]" (or host "localhost"))

      (.contains url "[[server-hostname]]")
      (string/replace "[[server-hostname]]" (.getHostName @localhost))

      (.contains url "[[server-ip]]")
      (string/replace "[[server-ip]]"       (.getHostAddress @localhost))

      (.contains url "[[server-port]]")
      (string/replace "[[server-port]]"     (str server-port)))))


(defn add-to-query [uri query-map]
  (let [[pre query] (string/split uri #"\?")]
    (str pre
         (when (or query (not-empty query-map))
             (str "?"
              (string/join "&"
                           (map (fn [[k v]]
                                  (str (name k)
                                       "="
                                  (java.net.URLEncoder/encode (str v) "UTF-8")))
                                query-map))
              (when (not (string/blank? query))
                (str "&" query)))))))

#_(add-to-query "ws://localhost:9500/figwheel-connect?hey=5" {:ab 'ab})

(defn config-connect-url [{:keys [::config repl-env-options] :as cfg} connect-id]
  (let [port (get-in repl-env-options [:ring-server-options :port] figwheel.repl/default-port)
        host (get-in repl-env-options [:ring-server-options :host] "localhost")
        connect-url
        (fill-connect-url-template
         (:connect-url config "ws://[[config-hostname]]:[[server-port]]/figwheel-connect")
         host
         port)]
    (add-to-query connect-url connect-id)))

#_(config-connect-url {} {:abb 1})

(defn config-repl-connect [{:keys [::config options ::build-name] :as cfg}]
  (let [connect-id (:connect-id config
                                (cond-> {:fwprocess process-unique}
                                  build-name (assoc :fwbuild build-name)))
        conn-url (config-connect-url cfg connect-id)
        fw-mode? (figwheel-mode? config options)]
    (cond-> cfg
      fw-mode?
      (update-in [:options :closure-defines] assoc 'figwheel.repl/connect-url conn-url)
      (and fw-mode? (not-empty connect-id))
      (assoc-in [:repl-env-options :connection-filter]
                (let [kys (keys connect-id)]
                  (fn [{:keys [query]}]
                    (= (select-keys query kys)
                       connect-id)))))))

;; hmmm could add init thunks into the config to be called once everything is initialized?

(defn config-open-file-command [{:keys [::config options] :as cfg}]
  (if-let [setup (and (:open-file-command config)
                      (figwheel-mode? config options)
                      (fw-util/require-resolve-var 'figwheel.main.editor/setup))]
    (do (setup (:open-file-command config))
        (update-in cfg [:options :preloads]
                   (fn [p] (vec (distinct (conj p 'figwheel.main.editor))))))
    cfg))

(defn config-watch-css [{:keys [::config options] :as cfg}]
  (cond-> cfg
    (and (not-empty (:css-dirs config))
         (figwheel-mode? config options))
    (update-in [:options :preloads]
               (fn [p] (vec (distinct (conj p 'figwheel.main.css-reload)))))))

(defn config-client-print-to [{:keys [::config] :as cfg}]
  (cond-> cfg
    (:client-print-to config)
    (update-in [:options :closure-defines] assoc
               'figwheel.repl/print-output
               (string/join "," (distinct (map name (:client-print-to config)))))))

#_(config-connect-url {::build-name "dev"})

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
       config-repl-connect
       config-client-print-to
       config-open-file-command
       config-watch-css
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

(defn get-repl-env-options [{:keys [repl-env-options] :as cfg}]
  (let [repl-options (get-repl-options cfg)]
    (merge
     repl-env-options
     (select-keys repl-options [:output-to :output-dir]))))

;; ----------------------------------------------------------------------------
;; Main action
;; ----------------------------------------------------------------------------

(defn build [{:keys [watch-dirs mode] :as config} options cenv]
  (let [source (when (and (= :none (:optimizations options :none)) (:main options))
                 (:uri (bapi/ns->location (symbol (:main options)))))]
    (if-let [paths (and (not= mode :build-once) (not-empty watch-dirs))]
      (do
        (bapi/build (apply bapi/inputs paths) options cenv)
        (watch paths options cenv (select-keys config [:reload-clj-files])))
      (cond
        source
        (bapi/build source options cenv)
        ;; TODO need :compile-paths config param
        (not-empty watch-dirs)
        (bapi/build (apply bapi/inputs watch-dirs) options cenv)))))

(defn repl [repl-env repl-options]
  (let [repl-fn (or (fw-util/require-resolve-var 'rebel-readline.cljs.repl/repl*)
                    cljs.repl/repl*)]
    (repl-fn repl-env repl-options)))

(defn serve [repl-env repl-options & [eval-str]]
  (cljs.repl/-setup repl-env repl-options)
  (when eval-str
    (cljs.repl/evaluate-form repl-env
                             (assoc (ana/empty-env)
                                    :ns (ana/get-namespace ana/*cljs-ns*))
                             "<cljs repl>"
                             ;; todo allow opts to be added here
                             (first (ana-api/forms-seq (StringReader. eval-str)))))
  (when-let [server @(:server repl-env)]
    (.join server)))

(defn update-server-host-port [repl-env [f address-port & args]]
  (if (and (#{"-s" "--serve"} f) address-port)
    (let [[_ host port] (re-matches #"(.*):(\d*)" address-port)]
      (cond-> repl-env
        (not (string/blank? host)) (assoc-in [:ring-server-options :host] host)
        (not (string/blank? port)) (assoc-in [:ring-server-options :port] (Integer/parseInt port))))
    repl-env))

(defn watch-css [css-dirs]
  (when-let [css-dirs (not-empty css-dirs)]
    (when-let [start-css (fw-util/require-resolve-var 'figwheel.main.css-reload/start*)]
      (start-css css-dirs))))

;; TODO what happens to inits like --init and --eval in all cases

;; TODO make this into a figwheel-start that
;; takes the correct config
;; and make an adapter function that produces the correct args for this fn
(defn default-compile [repl-env-fn cfg]
  (let [{:keys [ns args options repl-env-options ::config] :as cfg} (update-config cfg)
        {:keys [mode pprint-config]} config
        repl-env (apply repl-env-fn (mapcat identity (get-repl-env-options cfg)))
        cenv (cljs.env/default-compiler-env)]
    (cljs.env/with-compiler-env cenv
      (if pprint-config
        (do (clojure.pprint/pprint cfg) cfg)
        (binding [cljs.repl/*repl-env* repl-env]
          (let [fw-mode? (figwheel-mode? config options)]
            (build config options cenv)
            (when-not (= mode :build-once)
              ;; TODO move this to an list of ::init fns
              (watch-css (:css-dirs config))
              (cond
                (= mode :repl)
                ;; this forwards command line args
                (repl repl-env (get-repl-options cfg))
                (or (= mode :serve) fw-mode?)
                ;; we need to get the server host:port args
                (serve (update-server-host-port repl-env args)
                       (get-repl-options cfg)
                       ;; TODO need to iterate through the inits
                       (when fw-mode? (start-figwheel-code config)))))))))))

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
  (alter-var-root #'cli/default-commands cli/add-commands
                  figwheel-commands)
  (try
    (let [[pre post] (split-with (complement #{"-re" "--repl-env"}) args)
          args (if (empty? post) (concat ["-re" "figwheel"] args) args)
          args (handle-build-opt args)]
      (with-redefs [cljs.cli/default-compile default-compile]
        (apply cljs.main/-main args)))
    (catch Throwable e
      (let [d (ex-data e)]
        (if (or (:figwheel.main.schema/error d)
                (:cljs.main/error d))
          (println (.getMessage e))
          (throw e))))))

(def args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
