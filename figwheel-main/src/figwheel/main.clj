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
  (add-watch! (-> opts meta :build-id (or :dev))
              {:paths (if (coll? inputs) inputs [inputs])
               :filter (suffix-filter #{"cljs" "clj" "cljc"})
               :handler (throttle
                         50
                         (fn [evts]
                           (let [inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                             (figwheel.core/build inputs opts cenv))))}))

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
  (update-in cfg [::options-meta :watch] (fnil conj []) path))

(defn figwheel-opt [cfg bl]
  (assoc-in cfg [::options-meta :figwheel] (not= bl "false")))

(defn get-build [bn]
  (try (read-string (slurp (str bn ".cljs.edn")))
             (catch Throwable t
               (throw
                (ex-info (str "Couldn't read the build file: " (str bn ".cljs.edn"))
                         {:cljs.main/error :invalid-arg})))))

(defn path-parts [uri]
  (map str (java.nio.file.Paths/get uri)))

(defn watch-dir-from-ns [main-ns]
  (let [source (bapi/ns->location main-ns)
        local-dir (System/getProperty "user.dir")]
    (when-let [f (:uri source)]
      (let [parts (path-parts (.toURI f))
            local-dir-parts (path-parts (java.net.URI/create (str "file:" (System/getProperty "user.dir"))))]
        (when (and (= local-dir-parts (take (count local-dir-parts) parts))
                   (:relative-path source))
          (let [res (drop (count local-dir-parts) parts)
                end-parts (path-parts (java.net.URI/create (str "file:/" (:relative-path source))))]
            (when (= end-parts (take-last (count end-parts) res))
              (string/join (System/getProperty "file.separator") (drop-last (count end-parts) res)))))))))

(defn watch-dirs-from-build [{:keys [main] :as build-options}]
  (let [watch-dirs (-> build-options meta :watch-dirs)
        main-ns-dir (and main (watch-dir-from-ns (symbol main)))]
    (cons main-ns-dir watch-dirs)))

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
        (update-in [::options-meta :watch] concat watch-dirs)
        (assoc-in [::options-meta :build-id] bn)
        (assoc-in [::options-meta :build-options]
                  options))))

(defn build-once-opt [cfg bn]
  (let [cfg (build-opt cfg bn)]
    (assoc-in cfg [::options-meta :build-once] true)))

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

;; TODO
;; -b build flag
;; -bb background-build flag

;; TODO dev only

(def default-output-dir (.getPath (io/file "target" "public" "cljs-out")))
(def default-output-to  (.getPath (io/file "target" "public" "cljs-out" "main.js")))

(defn figure-default-asset-path [repl-options options]
  (let [{:keys [output-dir]} options
        stack-options (:ring-stack-options repl-options)]
    ;; if you have configured your static resources you are on your own
    (when (and
           (not (:asset-path options))
           (not (contains? stack-options :static)))
      (last (string/split (or output-dir default-output-dir)
                          #"[\/\\]public[\/\\]")))))



(defn default-compile
  [repl-env {:keys [ns args options ::options-meta] :as cfg}]
  (let [;; remember we have to fix this for the repl-opt as well
        ;; so that it understands multiple watch directories
        watch-dirs (distinct
                    (cond-> (:watch options-meta)
                      (:watch options) (conj (:watch options))))
        build-once? (:build-once options-meta)
        #_build-id    #_(str (:build-id options-meta (gensym 'dev_)) "_" process-unique)

        #_cfg       #_(assoc-in cfg [:repl-env-options :connection-filter]
                              (fn [{:keys [uri]}]
                                ))

        rfs      #{"-r" "--repl"}
        sfs      #{"-s" "--serve"}
        env-opts (cljs.repl/repl-options (repl-env))
        repl?    (boolean (or (rfs ns) (rfs (first args))))
        serve?   (boolean (or (sfs ns) (sfs (first args))))
        main-ns  (if (and ns (not ((into rfs sfs) ns)))
                   (symbol ns)
                   (:main options))
        figwheel-mode? (and (:figwheel options-meta true)
                            (not-empty watch-dirs)
                            (= :none (:optimizations options :none)))
        cfg      (if figwheel-mode?
                   (#'cljs.cli/eval-opt cfg "(figwheel.core/start-from-repl)")
                   cfg)

        opts     (as->
                   (merge
                     options
                     (when main-ns
                       {:main main-ns})) opts
                   (cond-> opts
                     (:watch opts)
                     (dissoc opts :watch)

                     (not (:output-to opts))
                     (assoc :output-to default-output-to)

                     (and (not (:asset-path opts)) (figure-default-asset-path env-opts opts))
                     (assoc :asset-path (figure-default-asset-path env-opts opts))

                     (not (:output-dir opts))
                     (assoc :output-dir default-output-dir)

                     figwheel-mode?
                     (update :preloads
                             (fn [p]
                               (vec (distinct
                                     (concat p '[figwheel.repl.preload figwheel.core])))))

                     (not (contains? opts :aot-cache))
                     (assoc :aot-cache false)))
        convey   (into [:output-dir :output-to] cljs.repl/known-repl-opts)
        cfg      (update cfg :options merge (select-keys opts convey))
        cfg      (update cfg :options dissoc :watch)

        source   (when (and (= :none (:optimizations opts :none)) main-ns)
                   (:uri (bapi/ns->location main-ns)))
        cenv     (cljs.env/default-compiler-env)]
    (assert (not (:watch opts)))
    (assert (not (:watch (:options cfg))))
    (cljs.env/with-compiler-env cenv
      (if-let [paths (and (not build-once?)
                          (not-empty watch-dirs))]
        (do
          (bapi/build (apply bapi/inputs paths) opts cenv)
          (watch paths opts cenv))
        (bapi/build source opts cenv))
      (when-not build-once?
        (when (and repl? (not serve?))
          (#'cli/repl-opt repl-env args cfg))
        (when (or serve? figwheel-mode?)
          ;; what to do when the repl isn't a figwheel repl here?
          ;; in the case where it isn't the repl we will need to start the server
          ;; directly, so perhaps start the server directly regardless

          ;; use repl to start server and setup environment for figwheel to operate in
          (let [re-opts (merge (:repl-env-options cfg)
                               (select-keys opts [:output-dir :output-to]))
                renv (apply repl-env (mapcat identity re-opts))]
            (binding [cljs.repl/*repl-env* renv]
              (cljs.repl/-setup renv (:options cfg))
              ;; TODO is this better than a direct call?
              ;; I don't think so
              (when figwheel-mode?
                (cljs.repl/evaluate-form renv
                                         (assoc (ana/empty-env)
                                                :ns (ana/get-namespace ana/*cljs-ns*))
                                         "<cljs repl>"
                                         ;; todo allow opts to be added here
                                         (first (ana-api/forms-seq (StringReader. "(figwheel.core/start-from-repl)")))))
              (when-let [server @(:server renv)]
                (.join server)))))))))

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
        args (if (empty? post) (concat ["-re" "figwheel" ] args) args)
        args (handle-build-opt args)]
    (with-redefs [cljs.cli/default-compile default-compile]
      (apply cljs.main/-main args))))

(def args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
