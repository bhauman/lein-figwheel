(ns figwheel.main
  #?(:clj
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
        [figwheel.repl :as fw-repl]
        [figwheel.main.logging :as log]
        [cljs.repl.figwheel]))
  #?(:clj
     (:import
      [java.io StringReader]
      [java.net.InetAddress]
      [java.net.URI]
      [java.net.URLEncoder]
      [java.nio.file.Paths]))
  #?(:cljs
     (:require-macros [figwheel.main])))

#?(:clj
   (do

;; TODO put this in figwheel config
#_(.setLevel log/*logger* java.util.logging.Level/ALL)

(defonce process-unique (subs (str (java.util.UUID/randomUUID)) 0 6))

(defn- time-elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

(defn- wrap-with-build-logging [build-fn]
  (fn [id? & args]
    (let [started-at (System/currentTimeMillis)
          {:keys [output-to output-dir]} (second args)]
      ;; print start message
      (log/info (str "Compiling build"
                     (when id? (str " " id?))
                     " to \""
                     (or output-to output-dir)))
      (try
        (apply build-fn args)
        (log/info (str "Successfully compiled build"
                       (when id? (str " " id?))
                       " to \""
                       (or output-to output-dir)
                       "\" in " (time-elapsed started-at) "."))
        (catch Throwable e
          (log/warn (str
                     "Failed to compile build" (when id? (str " " id?))
                     " in " (time-elapsed started-at) "."))
          (throw e))))))

(def build-cljs (wrap-with-build-logging bapi/build))
(def fig-core-build (wrap-with-build-logging figwheel.core/build))

;; simple protection against printing the compiler env
;; TODO this doesn't really work when it needs to
(defrecord WatchInfo [id paths options compiler-env reload-config])
(defmethod print-method WatchInfo [wi ^java.io.Writer writer]
  (.write writer (pr-str
                  (cond-> (merge {} wi)
                    (:compiler-env wi) (assoc :compiler-env 'compiler-env...)))))

(defn watch-build [id inputs opts cenv & [reload-config]]
  (when-let [inputs (if (coll? inputs) inputs [inputs])]
    (log/info "Watching build - " id)
    (fww/add-watch!
     [::autobuild id]
     (merge
      {::watch-info (merge
                     (:extra-info reload-config)
                     {:id id
                      :paths inputs
                      :options opts
                      :compiler-env cenv
                      :reload-config reload-config})}
      {:paths inputs
       :filter (fww/suffix-filter (into #{"cljs" "js"}
                                        (cond
                                          (coll? (:reload-clj-files reload-config))
                                          (mapv name (:reload-clj-files reload-config))
                                          (false? (:reload-clj-files reload-config)) []
                                          :else ["clj" "cljc"])))
       :handler (fww/throttle
                 50
                 (fn [evts]
                   (binding [cljs.env/*compiler* cenv]
                     (let [files (mapv (comp #(.getCanonicalPath %) :file) evts)
                           inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                       (try
                         (when-let [clj-files
                                    (not-empty
                                     (filter
                                      #(or (.endsWith % ".clj")
                                           (.endsWith % ".cljc"))
                                      files))]
                           (log/debug "Reloading clj files: " (pr-str (map str clj-files)))
                           (figwheel.core/reload-clj-files clj-files))
                         (log/debug "Detected changed cljs files: " (pr-str (map str files)))
                         (fig-core-build id inputs opts cenv files)
                         (catch Throwable t
                           #_(clojure.pprint/pprint
                              (Throwable->map t))
                           (figwheel.core/notify-on-exception cenv t {})
                           false))))))}))))

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
          (str "Watch path \"" path "\" does not exist")
          {:cljs.main/error :invalid-arg}))))
  (update-in cfg [::extra-config :watch-dirs] (fnil conj []) path))

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
      (log/debug "Validating metadata in build: " fname)
      (validate-config! (meta build)
                        (str "Configuration error in " fname)))
    build))

;; TODO this needs to be done later
(defn watch-dir-from-ns [main-ns]
  (let [source (bapi/ns->location main-ns)]
    (when-let [f (:uri source)]
      (let [res (fw-util/relativized-path-parts (.getPath f))
            end-parts (fw-util/path-parts (:relative-path source))]
        (when (= end-parts (take-last (count end-parts) res))
          (str (apply io/file (drop-last (count end-parts) res))))))))

;; TODO this needs to be done later
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
  (let [options (get-build bn)]
    (-> cfg
        (update :options merge options)
        (assoc-in [::build] (cond-> {:id bn}
                              (meta options)
                              (assoc :config (meta options)))))))

(defn build-once-opt [cfg bn]
  (let [cfg (build-opt cfg bn)]
    (assoc-in cfg [::config :mode] :build-once)))

(defn background-build-opt [cfg bn]
  (let [{:keys [options ::build]} (build-opt {} bn)]
    (update cfg ::background-builds
            (fnil conj [])
            (assoc build :options options))))

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
           :doc (str "The name of a build config to build once.")}
          ["-bb" "--background-build"]
          {:group :cljs.cli/compile :fn background-build-opt
           :arg "build-name"
           :doc (str "The name of a build config to watch and build in the background.")}}})

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

(defn extra-config-merge [a' b']
  (merge-with (fn [a b]
                (cond
                  (and (map? a) (map? b)) (merge a b)
                  (and (sequential? a)
                       (sequential? b))
                  (distinct (concat a b))
                  (nil? b) a
                  :else b))
              a' b'))

(defn process-figwheel-main-edn [{:keys [ring-handler] :as main-edn}]
  (log/info "Validating figwheel-main.edn")
  (validate-config! main-edn "Configuration error in figwheel-main.edn")
  (let [handler (and ring-handler (fw-util/require-resolve-var ring-handler))]
    (when (and ring-handler (not handler))
      (throw (ex-info "Unable to find :ring-handler" {:ring-handler ring-handler})))
    (cond-> main-edn
      handler (assoc :ring-handler handler))))

(defn fetch-figwheel-main-edn [cfg]
  (try (read-string (slurp (io/file "figwheel-main.edn")))
       (catch Throwable t
         (throw (ex-info "Problem reading figwheel-main.edn" )))))

(defn- config-figwheel-main-edn [cfg]
  (if-not (.exists (io/file "figwheel-main.edn"))
    cfg
    (let [config-edn (or (::start-figwheel-options cfg)
                         (process-figwheel-main-edn
                          (fetch-figwheel-main-edn cfg)))]
      (-> cfg
          (update ::config merge config-edn)))))

(defn- config-merge-current-build-conf [{:keys [::extra-config ::build] :as cfg}]
  (update cfg
          ::config #(extra-config-merge
                     (merge-with (fn [a b] (if b b a)) % (:config build))
                     extra-config)))

(defn host-port-arg? [arg]
  (and arg (re-matches #"(.*):(\d*)" arg)))

(defn update-server-host-port [config [f address-port & args]]
  (if (and (#{"-s" "--serve"} f) address-port)
    (let [[_ host port] (host-port-arg? address-port)]
      (cond-> config
        (not (string/blank? host)) (assoc-in [:ring-server-options :host] host)
        (not (string/blank? port)) (assoc-in [:ring-server-options :port] (Integer/parseInt port))))
    config))

;; targets options
(defn- config-main-ns [{:keys [ns options] :as cfg}]
  (let [main-ns (if (and ns (not (#{"-r" "--repl" "-s" "--serve"} ns)))
                  (symbol ns)
                  (:main options))]
    (cond-> cfg
      main-ns (assoc :ns main-ns)       ;; TODO not needed?
      main-ns (assoc-in [:options :main] main-ns))))

;; targets local config
(defn- config-repl-serve? [{:keys [ns args] :as cfg}]
  (let [rfs      #{"-r" "--repl"}
        sfs      #{"-s" "--serve"}]
    (cond-> cfg
      (boolean (or (rfs ns) (rfs (first args))))
      (assoc-in [::config :mode] :repl)
      (boolean (or (sfs ns) (sfs (first args))))
      (->
       (assoc-in [::config :mode] :serve)
       (update ::config update-server-host-port args))
      (rfs (first args))
      (update :args rest)
      (sfs (first args))
      (update :args rest)
      (and (sfs (first args)) (host-port-arg? (second args)))
      (update :args rest))))

;; targets local config
(defn- config-update-watch-dirs [{:keys [options ::config] :as cfg}]
  ;; remember we have to fix this for the repl-opt fn as well
  ;; so that it understands multiple watch directories
  (update-in cfg [::config :watch-dirs]
            #(not-empty
              (distinct
               (cond-> %
                 (:watch options) (conj (:watch options))
                 (:main options) (conj (watch-dir-from-ns (:main options))))))))

;; needs local config
(defn figwheel-mode? [{:keys [::config options]}]
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

;; targets options needs local config
(defn- config-figwheel-mode? [{:keys [::config options] :as cfg}]
  (cond-> cfg
    ;; check for a main??
    (figwheel-mode? cfg)
    (->
     (#'cljs.cli/eval-opt (start-figwheel-code config))
     (update-in [:options :preloads]
                (fn [p]
                  (vec (distinct
                        (concat p '[figwheel.repl.preload figwheel.core figwheel.main]))))))))

;; targets options
(defn- config-default-dirs [{:keys [options ::build] :as cfg}]
  (cond-> cfg
    (nil? (:output-to options))
    (assoc-in [:options :output-to] (default-output-to (:id build)))
    (nil? (:output-dir options))
    (assoc-in [:options :output-dir] (default-output-dir (:id build)))))

(defn figure-default-asset-path [{:keys [figwheel-options options ::build] :as cfg}]
  (let [{:keys [output-dir]} options]
    ;; TODO could discover the resource root if there is only one
    ;; or if ONLY static file serving can probably do something with that
    ;; as well
    ;; UNTIL THEN if you have configured your static resources no default asset-path
    (when-not (contains? (:ring-stack-options figwheel-options) :static)
      (let [parts (fw-util/relativized-path-parts (or output-dir (default-output-dir (:id build))))]
        (when-let [asset-path
                   (->> parts
                        (split-with (complement #{"public"}))
                        last
                        rest
                        not-empty)]
          (str (apply io/file asset-path)))))))

;; targets options
(defn- config-default-asset-path [{:keys [options] :as cfg}]
  (cond-> cfg
    (nil? (:asset-path options))
    (assoc-in [:options :asset-path] (figure-default-asset-path cfg))))

;; targets options
(defn- config-default-aot-cache-false [{:keys [options] :as cfg}]
  (cond-> cfg
    (not (contains? options :aot-cache))
    (assoc-in [:options :aot-cache] false)))

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
  (let [port (get-in config [:ring-server-options :port] figwheel.repl/default-port)
        host (get-in config [:ring-server-options :host] "localhost")
        connect-url
        (fill-connect-url-template
         (:connect-url config "ws://[[config-hostname]]:[[server-port]]/figwheel-connect")
         host
         port)]
    (add-to-query connect-url connect-id)))

#_(config-connect-url {} {:abb 1})

(defn config-repl-connect [{:keys [::config options ::build] :as cfg}]
  (let [connect-id (:connect-id config
                                (cond-> {:fwprocess process-unique}
                                  (:id build) (assoc :fwbuild (:id build))))
        conn-url (config-connect-url cfg connect-id)
        fw-mode? (figwheel-mode? cfg)]
    (cond-> cfg
      fw-mode?
      (update-in [:options :closure-defines] assoc 'figwheel.repl/connect-url conn-url)
      (and fw-mode? (not-empty connect-id))
      (assoc-in [:repl-env-options :connection-filter]
                (let [kys (keys connect-id)]
                  (fn [{:keys [query]}]
                    (= (select-keys query kys)
                       connect-id)))))))

(defn config-open-file-command [{:keys [::config options] :as cfg}]
  (if-let [setup (and (:open-file-command config)
                      (figwheel-mode? cfg)
                      (fw-util/require-resolve-var 'figwheel.main.editor/setup))]
    (-> cfg
        (update ::initializers (fnil conj []) #(setup (:open-file-command config)))
        (update-in [:options :preloads]
                   (fn [p] (vec (distinct (conj p 'figwheel.main.editor))))))
    cfg))

(defn watch-css [css-dirs]
  (when-let [css-dirs (not-empty css-dirs)]
    (when-let [start-css (fw-util/require-resolve-var 'figwheel.main.css-reload/start*)]
      (start-css css-dirs))))

(defn config-watch-css [{:keys [::config options] :as cfg}]
  (cond-> cfg
    (and (not-empty (:css-dirs config))
         (figwheel-mode? cfg))
    (->
     (update ::initializers (fnil conj []) #(watch-css (:css-dirs config)))
     (update-in [:options :preloads]
                (fn [p] (vec (distinct (conj p 'figwheel.main.css-reload))))))))

(defn config-client-print-to [{:keys [::config] :as cfg}]
  (cond-> cfg
    (:client-print-to config)
    (update-in [:options :closure-defines] assoc
               'figwheel.repl/print-output
               (string/join "," (distinct (map name (:client-print-to config)))))))

(defn get-repl-options [{:keys [options args inits repl-options] :as cfg}]
  (assoc (merge (dissoc options :main)
                repl-options)
         :inits
         (into
          [{:type :init-forms
            :forms (when-not (empty? args)
                     [`(set! *command-line-args* (list ~@args))])}]
          inits)))

(defn get-repl-env-options [{:keys [repl-env-options ::config] :as cfg}]
  (let [repl-options (get-repl-options cfg)]
    (merge
     (select-keys config
                  [:ring-server
                   :ring-server-options
                   :ring-stack
                   :ring-stack-options
                   :ring-handler])
     repl-env-options ;; from command line
     (select-keys repl-options [:output-to :output-dir]))))

(defn config-finalize-repl-options [cfg]
  (let [repl-options (get-repl-options cfg)
        repl-env-options (get-repl-env-options cfg)]
    (assoc cfg
           :repl-options repl-options
           :repl-env-options repl-env-options)))

#_(config-connect-url {::build-name "dev"})

(defn update-config [cfg]
  (->> cfg
       config-figwheel-main-edn
       config-merge-current-build-conf
       config-repl-serve?
       config-main-ns
       config-update-watch-dirs
       config-figwheel-mode?
       config-default-dirs
       config-default-asset-path
       config-default-aot-cache-false
       config-repl-connect
       config-client-print-to
       config-open-file-command
       config-watch-css
       config-finalize-repl-options
       config-clean))

;; ----------------------------------------------------------------------------
;; Main action
;; ----------------------------------------------------------------------------

(defn build [{:keys [watch-dirs mode ::build] :as config} options cenv]
  (let [source (when (and (= :none (:optimizations options :none)) (:main options))
                 (:uri (bapi/ns->location (symbol (:main options)))))]
    ;; TODO should probably try obtain a watch path from :main here
    ;; if watch-dirs is empty
    (if-let [paths (and (not= mode :build-once) (not-empty watch-dirs))]
      (do
        (build-cljs (:id build "dev") (apply bapi/inputs paths) options cenv)
        (watch-build (:id build "dev")
                     paths options cenv (select-keys config [:reload-clj-files])))
      (cond
        source
        (build-cljs (:id build "dev") source options cenv)
        ;; TODO need :compile-paths config param
        (not-empty watch-dirs)
        (build-cljs (:id build "dev") (apply bapi/inputs watch-dirs) options cenv)))))

(defn starting-server-log [repl-env]
  (let [host (get-in repl-env [:ring-server-options :host] "localhost")
        port (get-in repl-env [:ring-server-options :port] figwheel.repl/default-port)
        scheme (if (get-in repl-env [:ring-server-options :ssl?])
                 "https" "http")]
    (log/info "Starting Server at " scheme "://" host ":" port )))

;; TODO this needs to work in nrepl as well
(defn repl [repl-env repl-options]
  (starting-server-log repl-env)
  (log/info "Starting REPL")
  (let [repl-fn (or (fw-util/require-resolve-var 'rebel-readline.cljs.repl/repl*)
                    cljs.repl/repl*)]
    (repl-fn repl-env repl-options)))

(defn serve [{:keys [repl-env repl-options eval-str join?]}]
  (starting-server-log repl-env)
  (cljs.repl/-setup repl-env repl-options)
  (when eval-str
    (cljs.repl/evaluate-form repl-env
                             (assoc (ana/empty-env)
                                    :ns (ana/get-namespace ana/*cljs-ns*))
                             "<cljs repl>"
                             ;; todo allow opts to be added here
                             (first (ana-api/forms-seq (StringReader. eval-str)))))

  (when-let [server (and join? @(:server repl-env))]
    (.join server)))

(defn background-build [cfg {:keys [id config options]}]
  (let [{:keys [::build ::config repl-env-options] :as cfg}
        (-> (select-keys cfg [::start-figwheel-options])
            (assoc :options options
                   ::build {:id id :config config})
            update-config)
        cenv (cljs.env/default-compiler-env)]
    (when (not-empty (:watch-dirs config))
      (log/info "Starting background autobuild - " (:id build))
      (binding [cljs.env/*compiler* cenv]
        (build-cljs (:id build) (apply bapi/inputs (:watch-dirs config)) (:options cfg) cenv)
        (watch-build (:id build)
                     (:watch-dirs config)
                     (:options cfg)
                     cenv
                     (select-keys config [:reload-clj-files]))
        ;; TODO need to move to this pattern instead of repl evals
        (when (first (filter #{'figwheel.core} (:preloads (:options cfg))))
          (binding [cljs.repl/*repl-env* (figwheel.repl/repl-env*
                                          (select-keys repl-env-options
                                                       [:connection-filter]))]
            (figwheel.core/start*)))))))

(defn start-background-builds [{:keys [::background-builds] :as cfg}]
  (doseq [build background-builds]
    (background-build cfg build)))

(def ^:dynamic *base-config*)
(def ^:dynamic *config*)

;; TODO what happens to inits like --init and --eval in all cases

;; TODO make this into a figwheel-start that
;; takes the correct config
;; and make an adapter function that produces the correct args for this fn
(defn default-compile [repl-env-fn cfg]
  (let [{:keys [options repl-options repl-env-options ::config] :as b-cfg} (update-config cfg)
        {:keys [mode pprint-config]} config
        repl-env (apply repl-env-fn (mapcat identity repl-env-options))
        cenv (cljs.env/default-compiler-env options)]
    (binding [*base-config* cfg
              *config* b-cfg]
      (cljs.env/with-compiler-env cenv
        (if pprint-config
          (do (clojure.pprint/pprint b-cfg) b-cfg)
          (binding [cljs.repl/*repl-env* repl-env]
            (let [fw-mode? (figwheel-mode? b-cfg)]
              (build config options cenv)
              (when-not (= mode :build-once)
                (start-background-builds (assoc cfg
                                                ::start-figwheel-options
                                                config))
                (doseq [init-fn (::initializers b-cfg)] (init-fn))
                (cond
                  (= mode :repl)
                  ;; this forwards command line args
                  (repl repl-env repl-options)
                  (or (= mode :serve) fw-mode?)
                  ;; we need to get the server host:port args
                  (serve {:repl-env repl-env
                          :repl-options repl-options
                          ;; TODO need to iterate through the inits
                          :eval-str (when fw-mode? (start-figwheel-code config))
                          :join? (get b-cfg ::join-server? true)}))))))))))

(defn start [{:keys [figwheel-options build join-server?]}]
  (let [[build-id build-options] (if (map? build)
                                   [(:id build) (:options build)]
                                   [build])
        build-id (name (or build-id "dev"))
        options  (or (and (not build-options)
                          (get-build build-id))
                     build-options
                     {})
        cfg
        (cond-> {:options options
                 ::join-server? (if (true? join-server?) true false)}
          figwheel-options (assoc ::start-figwheel-options figwheel-options)
          build-id    (assoc ::build {:id  build-id
                                      :config (meta options)})
          true        (update-in [::start-figwheel-options :mode] #(if-not % :repl %)))]
    (default-compile cljs.repl.figwheel/repl-env cfg)))

;; ----------------------------------------------------------------------------
;; REPL api
;; ----------------------------------------------------------------------------

(defn currently-watched-ids []
  (set (map second (filter
               #(and (coll? %) (= (first %) ::autobuild))
               (keys (:watches @fww/*watcher*))))))

(defn currently-available-ids []
  (into (currently-watched-ids)
        (map second (keep #(when (fww/real-file? %)
                 (re-matches #"(.+)\.cljs\.edn" (.getName %)))
                          (file-seq (io/file "."))))))

(defn config-for-id [id]
  (update-config (build-opt *base-config* "dev")))

(defn clean-build [{:keys [output-to output-dir]}]
  (when (and output-to output-dir)
    (doseq [file (cons (io/file output-to)
                       (reverse (file-seq (io/file output-dir))))]
      (when (.exists file) (.delete file)))))

(defn select-autobuild-watches [ids]
  (->> ids
       (map #(vector ::autobuild %))
       (select-keys (:watches @fww/*watcher*))
       vals))

(defn warn-on-bad-id [ids]
  (when-let [bad-ids (not-empty (remove (currently-watched-ids) ids))]
    (doseq [bad-id bad-ids]
      (println "No autobuild currently has id:" bad-id))))

;; TODO this should clean ids that are not currently running as well
;; TODO should this default to cleaning all builds??
;; I think yes
(defn clean* [ids]
  (let [ids (->> ids (map name) distinct)]
    (warn-on-bad-id ids)
    (doseq [watch' (select-autobuild-watches ids)]
      #_(prn watch')
      (when-let [options (-> watch' ::watch-info :options)]
        (println "Cleaning build id:" (-> watch' ::watch-info :id))
        (clean-build options)))))

(defmacro clean [& ids]
  (clean* (map name ids))
  nil)

(defn status* []
  (println "------- Figwheel Main Status -------")
  (if-let [ids (not-empty (currently-watched-ids))]
    (println "Currently building:" (string/join ", " ids))
    (println "No builds are currently being built.")))

(defmacro status []
  (status*) nil)

(defn stop-builds* [ids]
  (let [ids (->> ids (map name) distinct)]
    (warn-on-bad-id ids)
    (doseq [k (map #(vector ::autobuild %) ids)]
      (when (-> fww/*watcher* deref :watches (get k))
        (println "Stopped building id:" (last k))
        (fww/remove-watch! k)))))

;; TODO should this default to stopping all builds??
;; I think yes
(defmacro stop-builds [& ids]
  (stop-builds* ids)
  nil)

(defn main-build? [id]
  (and *config* (= (name id) (-> *config* ::build :id))))

(defn hydrate-all-background-builds [cfg ids]
  (reduce background-build-opt (dissoc cfg ::background-builds) ids))

(defn start-builds* [ids]
  (let [ids (->> ids (map name) distinct)
        already-building (not-empty (filter (currently-watched-ids) ids))
        ids (filter (complement (currently-watched-ids)) ids)]
    (when (not-empty already-building)
      (doseq [i already-building]
        (println "Already building id: " i)))
    (let [main-build-id     (first (filter main-build? ids))
          bg-builds (remove main-build? ids)]
      #_(prn main-build-id bg-builds)
      (when main-build-id
        (let [{:keys [options repl-env-options ::config]} *config*
              {:keys [watch-dirs]} config]
          (println "Starting build id:" main-build-id)
          ;; TODO these fns should provide feedback internally
          (bapi/build (apply bapi/inputs watch-dirs) options cljs.env/*compiler*)
          (watch-build main-build-id
                       watch-dirs options
                       cljs.env/*compiler*
                       (select-keys config [:reload-clj-files]))
          (when (first (filter #{'figwheel.core} (:preloads options)))
            (binding [cljs.repl/*repl-env* (figwheel.repl/repl-env*
                                            (select-keys repl-env-options
                                                         [:connection-filter]))]
              (figwheel.core/start*)))))
      (when (not-empty bg-builds)
        (let [cfg (hydrate-all-background-builds
                   {::start-figwheel-options (::config *config*)}
                   bg-builds)]
          (start-background-builds cfg))))))

;; TODO should this default to stopping all builds??
;; I think yes
(defmacro start-builds [& ids]
  (start-builds* ids)
  nil)

(defn reload-config* []
  (println "Reloading config!")
  (set! *config* (update-config *base-config*)))

(defn reset* [ids]
  (let [ids (->> ids (map name) distinct)
        ids (or (not-empty ids) (currently-watched-ids))]
    (clean* ids)
    (stop-builds* ids)
    (reload-config*)
    (start-builds* ids)
    nil))

(defmacro reset [& ids]
  (reset* ids))

(defn build-once* [ids]
  (let [ids (->> ids (map name) distinct)
        bad-ids (filter (complement (currently-available-ids)) ids)
        good-ids (filter (currently-available-ids) ids)]
    (when (not-empty bad-ids)
      (doseq [i bad-ids]
        (println "Build id not found:" i)))
    (when (not-empty good-ids)
      ;; clean?
      (doseq [i good-ids]
        (let [{:keys [options ::config]} (config-for-id i)
              input (if-let [paths (not-empty (:watch-dirs config))]
                      (apply bapi/inputs paths)
                      (when-let [source (when (:main options)
                                          (:uri (bapi/ns->location (symbol (:main options)))))]
                        source))]
          (when input
            (build-cljs i input options
                         (cljs.env/default-compiler-env options))))))))

(defmacro build-once [& ids]
  (build-once* ids)
  nil)

;; TODO reset, build-once

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

;; ----------------------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------------------

(def server (atom nil))

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

))

#_(def test-args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
