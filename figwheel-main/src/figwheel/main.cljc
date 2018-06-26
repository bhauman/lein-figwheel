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
        [cljs.repl.figwheel]
        [cljs.util]
        [clojure.java.io :as io]
        [clojure.pprint :refer [pprint]]
        [clojure.string :as string]
        [clojure.edn :as edn]
        [clojure.tools.reader.edn :as redn]
        [clojure.tools.reader.reader-types :as rtypes]
        [figwheel.core :as fw-core]
        [figwheel.main.ansi-party :as ansip]
        [figwheel.main.logging :as log]
        [figwheel.main.util :as fw-util]
        [figwheel.main.watching :as fww]
        [figwheel.main.helper :as helper]
        [figwheel.repl :as fw-repl]
        [figwheel.tools.exceptions :as fig-ex]))
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

(def ^:dynamic *base-config*)
(def ^:dynamic *config*)

(def default-target-dir "target")

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
                     (or output-to output-dir)
                     "\""))
      (try
        (let [warnings (volatile! [])
              out *out*
              warning-fn (fn [warning-type env extra]
                            (when (get cljs.analyzer/*cljs-warnings* warning-type)
                              (let [warn {:warning-type warning-type
                                          :env env
                                          :extra extra
                                          :path ana/*cljs-file*}]
                                (binding [*out* out]
                                  (if (<= (count @warnings) 2)
                                    (log/cljs-syntax-warning warn)
                                    (binding [log/*syntax-error-style* :concise]
                                      (log/cljs-syntax-warning warn))))
                                (vswap! warnings conj warn))))]
          (binding [cljs.analyzer/*cljs-warning-handlers*
                    (conj (remove #{cljs.analyzer/default-warning-handler}
                                  cljs.analyzer/*cljs-warning-handlers*)
                          warning-fn)]
            (apply build-fn args)))
        (log/succeed (str "Successfully compiled build"
                       (when id? (str " " id?))
                       " to \""
                       (or output-to output-dir)
                       "\" in " (time-elapsed started-at) "."))
        (catch Throwable e
          (log/failure (str
                        "Failed to compile build" (when id? (str " " id?))
                        " in " (time-elapsed started-at) "."))
          (log/syntax-exception e)
          (throw e))))))

(def build-cljs (wrap-with-build-logging bapi/build))
(def fig-core-build (wrap-with-build-logging figwheel.core/build))

(defn watch-build [id inputs opts cenv & [reload-config]]
  (when-let [inputs (if (coll? inputs) inputs [inputs])]
    (let [build-inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)
          ;; the build-fn needs to be passed in before here?
          build-fn (if (some #{'figwheel.core} (:preloads opts))
                     #(fig-core-build id build-inputs opts cenv %)
                     (fn [files] (build-cljs id build-inputs opts cenv)))]
      (log/info "Watching and compiling paths:" (pr-str inputs) "for build -" id)
      (binding [fww/*hawk-options* (:hawk-options reload-config nil)]
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
                     (:wait-time-ms reload-config 50)
                     (bound-fn [evts]
                       (binding [cljs.env/*compiler* cenv]
                         (let [files (mapv (comp #(.getCanonicalPath %) :file) evts)]
                           (try
                             (when-let [clj-files
                                        (not-empty
                                         (filter
                                          #(or (.endsWith % ".clj")
                                               (.endsWith % ".cljc"))
                                          files))]
                               (log/debug "Reloading clj files: " (pr-str (map str clj-files)))
                               (try
                                 (figwheel.core/reload-clj-files clj-files)
                                 (catch Throwable t
                                   (log/syntax-exception t)
                                   (figwheel.core/notify-on-exception cenv t {})
                                   (throw t))))
                             (log/debug "Detected changed cljs files: " (pr-str (map str files)))
                             (build-fn files)
                             ;; exceptions are reported by the time they get to here
                             (catch Throwable t false))))))}))))))

(declare read-edn-file)

(defn get-edn-file-key
  ([edn-file key] (get-edn-file-key edn-file key nil))
  ([edn-file key default]
   (try (get (read-string (slurp edn-file)) key default)
        (catch Throwable t default))))

(def validate-config!*
  (when (try
          (require 'clojure.spec.alpha)
          (require 'expound.alpha)
          (require 'expound.ansi)
          (require 'figwheel.main.schema.config)
          (require 'figwheel.main.schema.cljs-options)
          (require 'figwheel.main.schema.cli)
          true
          (catch Throwable t false))
    (resolve 'figwheel.main.schema.core/validate-config!)))

(defn validate-config! [spec edn fail-msg & [succ-msg]]
  (when (and validate-config!*
             (not
              (false?
               (:validate-config
                edn
                (get-edn-file-key "figwheel-main.edn" :validate-config)))))
    (expound.ansi/with-color-when (:ansi-color-output edn true)
      (validate-config!* spec edn fail-msg))
    (when succ-msg
      (log/succeed succ-msg))))

(def validate-cli!*
  (when validate-config!*
    (resolve 'figwheel.main.schema.cli/validate-cli!)))

(defn validate-cli! [cli-args & [succ-msg]]
  (when (and validate-cli!*
             (get-edn-file-key "figwheel-main.edn" :validate-cli true))
    (expound.ansi/with-color-when
      (get-edn-file-key "figwheel-main.edn" :ansi-color-output true)
      (validate-cli!* cli-args "Error in command line args"))
    (when succ-msg
      (log/succeed succ-msg))))

;; ----------------------------------------------------------------------------
;; Additional cli options
;; ----------------------------------------------------------------------------

;; Help


(def help-template
  "Usage: clojure -m figwheel.main [init-opt*] [main-opt] [arg*]

Common usage:
  clj -m figwheel.main -b dev -r
Which is equivalient to:
  clj -m figwheel.main -co dev.cljs.edn -c example.core -r

In the above example, dev.cljs.edn is a file in the current directory
that holds a build configuration which is a Map of ClojureScript
compile options. In the above command example.core is ClojureScript
namespace on your classpath that you want to compile.

A minimal dev.cljs.edn will look similar to:
{:main example.core}

The above command will start a watch process that will compile your
source files when one of them changes, it will also facilitate
communication between this watch process and your JavaScript
environment (normally a browser window) so that it can hot reload
changed code into the environment. After the initial compile, it
will then launch a browser to host your compiled ClojureScript code,
and finally a CLJS REPL will launch.

Configuration:

In the above example, besides looking for a dev.cljs.edn file,
figwheel.main will also look for a figwheel-main.edn file in the
current directory as well.

A list of all the config options can be found here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/doc/figwheel-main-options.md

A list of ClojureScript compile options can be found here:
https://clojurescript.org/reference/compiler-options

You can add build specific figwheel.main configuration in the
*.cljs.edn file by adding metadata to the build config file like
so:

^{:watch-dirs [\"dev\" \"cljs-src\"]}
{:main example.core}

Command Line Options:

With no options or args, figwheel.main runs a ClojureScript REPL

%s
For --main and --repl:

  - Enters the cljs.user namespace
  - Binds *command-line-args* to a seq of strings containing command line
    args that appear after any main option
  - Runs all init options in order
  - Calls a -main function or runs a repl or script if requested

The init options may be repeated and mixed freely, but must appear before
any main option.

In the case of --compile and --build you may supply --repl or --serve
options afterwards.

Paths may be absolute or relative in the filesystem or relative to
classpath. Classpath-relative paths have prefix of @ or @/")

(defn adjust-option-docs [commands]
  (-> commands
      (update-in [:groups :cljs.cli/main&compile :pseudos]
                 dissoc ["-re" "--repl-env"])
      (assoc-in [:init ["-d" "--output-dir"] :doc]
                "Set the output directory to use")
      (update-in [:init ["-w" "--watch"] :doc] str
                ". This option can be supplied multiple times.")))

(defn help-str [repl-env]
  (format
   help-template
   (#'cljs.cli/options-str
    (adjust-option-docs
     (#'cljs.cli/merged-commands repl-env)))))

(defn help-opt
  [repl-env _ _]
  (println (help-str repl-env)))

;; safer option reading from files which prints out syntax errors

(defn read-edn-file [f]
  (try (redn/read
        (rtypes/source-logging-push-back-reader (io/reader f) 1 f))
       (catch Throwable t
         (log/syntax-exception t)
         (throw
          (ex-info (str "Couldn't read the file:" f)
                   {::error true} t)))))

(defn read-edn-string [s & [fail-msg]]
  (try
    (redn/read
     (rtypes/source-logging-push-back-reader (io/reader (.getBytes s)) 1))
    (catch Throwable t
      (let [except-data (fig-ex/add-excerpt (fig-ex/parse-exception t) s)]
        (log/info (ansip/format-str (log/format-ex except-data)))
        (throw (ex-info (str (or fail-msg "Failed to read EDN string: ")
                             (.getMessage t))
                        {::error true}
                        t))))))

(defn read-edn-opts [str]
  (letfn [(read-rsrc [rsrc-str orig-str]
            (if-let [rsrc (io/resource rsrc-str)]
              (read-edn-string (slurp rsrc))
              (cljs.cli/missing-resource orig-str)))]
    (cond
     (string/starts-with? str "@/") (read-rsrc (subs str 2) str)
     (string/starts-with? str "@") (read-rsrc (subs str 1) str)
     :else
     (let [f (io/file str)]
       (if (.isFile f)
         (read-edn-file f)
         (cljs.cli/missing-file str))))))

(defn merge-meta [m m] (with-meta (merge m m) (merge (meta m) (meta m))))

(defn load-edn-opts [str]
  (reduce merge-meta {} (map read-edn-opts (cljs.util/split-paths str))))

(defn fallback-id [edn]
  (let [m (meta edn)]
    (cond
      (and (:id m) (not (string/blank? (str (:id m)))))
      (:id m)
      ;;(:main edn)      (munge (str (:main edn)))
      :else
      (str "build-"
           (.getValue (doto (java.util.zip.CRC32.)
                        (.update (.getBytes (pr-str (into (sorted-map) edn))))))))))

(defn compile-opts-opt
  [cfg copts]
  (let [copts (string/trim copts)
        edn   (if (or (string/starts-with? copts "{")
                      (string/starts-with? copts "^"))
                (read-edn-string copts "Error reading EDN from command line flag: -co ")
                (load-edn-opts copts))
        config  (meta edn)
        id
        (and edn
             (if (or (string/starts-with? copts "{")
                     (string/starts-with? copts "^"))
               (and (map? edn) (fallback-id edn))
               (->>
                (cljs.util/split-paths copts)
                (filter (complement string/blank?))
                (filter #(not (.startsWith % "@")))
                (map io/file)
                (map (comp first #(string/split % #"\.") #(.getName %)))
                (string/join ""))))]
    (log/debug "Validating options passed to --compile-opts")
    (validate-config!
     :figwheel.main.schema.cljs-options/cljs-options
     edn
     (str "Configuration error in options passed to --compile-opts"))
    (cond-> cfg
      edn (update :options merge edn)
      id  (update-in [::build :id] #(if-not % id %))
      config (update-in [::build :config] merge config))))

(defn repl-env-opts-opt
  [cfg ropts]
  (let [ropts (string/trim ropts)
        edn   (if (string/starts-with? ropts "{")
                (read-edn-string ropts "Error reading EDN from command line flag: --repl-opts ")
                (load-edn-opts ropts))]
    (update cfg :repl-env-options merge edn)))

(defn figwheel-opts-opt
  [cfg ropts]
  (let [ropts (string/trim ropts)
        edn   (if (string/starts-with? ropts "{")
                (read-edn-string ropts "Error reading EDN from command line flag: -fw-opts ")
                (load-edn-opts ropts))]
    (validate-config!
     :figwheel.main.schema.config/edn
     edn "Error validating figwheel options EDN provided to -fwo CLI flag")
    (update cfg ::config merge edn)))

(defn print-config-opt [cfg opt]
  (assoc-in cfg [::config :pprint-config] (not (#{"false"} opt))))

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
  (assoc-in cfg [::config :figwheel-core] (not= bl "false")))

(defn get-build [bn]
  (let [fname (if (.contains bn (System/getProperty "path.separator"))
                bn
                (str bn ".cljs.edn"))
        build (->> (cljs.util/split-paths bn)
                   (map #(str % ".cljs.edn"))
                   (string/join (System/getProperty "path.separator"))
                   load-edn-opts)]
    (when build
      (when-not (false? (:validate-config (meta build)))
        (when (meta build)
          (log/debug "Validating metadata in build: " fname)
          (validate-config!
           :figwheel.main.schema.config/edn
           (meta build)
           (str "Configuration error in build options meta data: " fname)))
        (log/debug "Validating CLJS compile options for build:" fname)
        (validate-config!
         :figwheel.main.schema.cljs-options/cljs-options
         build
         (str "Configuration error in CLJS compile options: " fname))))
    build))

(defn watch-dir-from-ns [main-ns]
  (let [source (fw-util/ns->location main-ns)]
    (when-let [f (:uri source)]
      (when (= "file" (.getScheme (.toURI f)))
        (let [res (fw-util/relativized-path-parts (.getPath f))
              end-parts (fw-util/path-parts (:relative-path source))]
          (when (= end-parts (take-last (count end-parts) res))
            (str (apply io/file (drop-last (count end-parts) res)))))))))

(def default-main-repl-index-body
  (str
   "<p>Welcome to the Figwheel REPL page.</p>"
   "<p>This page is served when you launch <code>figwheel.main</code> without any command line arguments.</p>"
   "<p>This page is currently hosting your REPL and application evaluation environment. "
   "Validate the connection by typing <code>(js/alert&nbsp;\"Hello&nbsp;Figwheel!\")</code> in the REPL.</p>"))

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
        (assoc  ::build (cond-> {:id bn}
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

;; TODO move these down to main action section

(declare default-compile)

(defn build-main-opt [repl-env-fn [_ build-name & args] cfg]
  ;; serve if no other args
  (let [args (if-not (#{"-s" "-r" "--repl" "--serve"} (first args))
               (cons "-s" args)
               args)]
    (default-compile repl-env-fn
                     (merge (build-opt cfg build-name)
                            {:args args
                             ::build-main-opt true}))))

(defn build-once-main-opt [repl-env-fn [_ build-name & args] cfg]
  (default-compile repl-env-fn
                   (merge (build-once-opt cfg build-name)
                          {:args args})))

(declare default-output-dir default-output-to)

(defn make-temp-dir []
  (let [tempf (java.io.File/createTempFile "figwheel" "repl")]
    (.delete tempf)
    (.mkdirs tempf)
    (.deleteOnExit (io/file tempf))
    (fw-util/add-classpath! (.toURL tempf))
    tempf))

(defn add-temp-dir [cfg]
  (let [temp-dir (make-temp-dir)
        config-with-target (assoc-in cfg [::config :target-dir] temp-dir)
        output-dir (default-output-dir config-with-target)
        output-to  (default-output-to config-with-target)]
    (-> cfg
        (assoc-in [:options :output-dir] output-dir)
        (assoc-in [:options :output-to]  output-to)
        (assoc-in [:options :asset-path]
                  (str "cljs-out"
                       (when-let [id (-> cfg ::build :id)]
                         (str (System/getProperty "file.separator")
                              id)))))))

(defn should-add-temp-dir? [cfg]
  (let [target-on-classpath?
        (when-let [target-dir (get-edn-file-key
                               "figwheel-main.edn"
                               :target-dir
                               default-target-dir)]
          (fw-util/dir-on-classpath? target-dir))]
    (and (nil? (:target (:options cfg)))
         (not target-on-classpath?))))

(defn helper-ring-app [handler html-body output-to & [force-index?]]
  (figwheel.server.ring/default-index-html
   handler
   (figwheel.server.ring/index-html (cond-> {}
                                      html-body (assoc :body html-body)
                                      output-to (assoc :output-to output-to)))
   force-index?))

(defn repl-main-opt [repl-env-fn args cfg]
  (let [cfg (if (should-add-temp-dir? cfg)
              (add-temp-dir cfg)
              cfg)
        cfg (if (get-in cfg [::build :id])
              cfg
              (assoc-in cfg [::build :id] "figwheel-default-repl-build"))
        output-to (get-in cfg [:options :output-to]
                          (default-output-to cfg))]
    (default-compile
     repl-env-fn
     (-> cfg
         (assoc :args args)
         (update :options (fn [opt] (merge {:main 'figwheel.repl.preload} opt)))
         (assoc-in [:options :aot-cache] true)
         (assoc-in [::config
                    :ring-stack-options
                    :figwheel.server.ring/dev
                    :figwheel.server.ring/system-app-handler]
                   #(helper/middleware
                     %
                     {:header "REPL Host page"
                      :body (slurp (io/resource "public/com/bhauman/figwheel/helper/content/repl_welcome.html"))
                      :output-to output-to}))
         (assoc-in [::config :mode] :repl)))))

(declare serve update-config)

(defn print-conf [cfg]
  (println "---------------------- Figwheel options ----------------------")
  (pprint (::config cfg))
  (println "---------------------- Compiler options ----------------------")
  (pprint (:options cfg)))

(defn serve-main-opt [repl-env-fn args b-cfg]
  (let [{:keys [repl-env-options repl-options options] :as cfg}
        (-> b-cfg
            (assoc :args args)
            update-config)
        repl-env-options
        (update-in
         repl-env-options
         [:ring-stack-options
          :figwheel.server.ring/dev
          :figwheel.server.ring/system-app-handler]
         (fn [sah]
           (if sah
             sah
             #(helper/serve-only-middleware % {}))))
        {:keys [pprint-config]} (::config cfg)
        repl-env (apply repl-env-fn (mapcat identity repl-env-options))]
    (log/trace "Verbose config:" (with-out-str (pprint cfg)))
    (if pprint-config
      (do
        (log/info ":pprint-config true - printing config:")
        (print-conf cfg))
      (serve {:repl-env repl-env
              :repl-options repl-options
              :join? true}))))

(def figwheel-commands
  {:init {["-w" "--watch"]
          {:group :cljs.cli/compile :fn watch-opt
           :arg "path"
           :doc "Continuously build, only effective with the --compile and --build main options"}
          ["-fwo" "--fw-opts"]
          {:group :cljs.cli/compile :fn figwheel-opts-opt
           :arg "edn"
           :doc (str "Options to configure figwheel.main, can be an EDN string or "
                     "system-dependent path-separated list of EDN files / classpath resources. Options "
                     "will be merged left to right.")}
          ["-ro" "--repl-opts"]
          {:group ::main&compile :fn repl-env-opts-opt
           :arg "edn"
           :doc (str "Options to configure the repl-env, can be an EDN string or "
                     "system-dependent path-separated list of EDN files / classpath resources. Options "
                     "will be merged left to right.")}
          ["-co" "--compile-opts"]
          {:group :cljs.cli/main&compile :fn compile-opts-opt
           :arg "edn"
           :doc (str "Options to configure the build, can be an EDN string or "
                     "system-dependent path-separated list of EDN files / classpath resources. Options "
                     "will be merged left to right. Any meta data will be merged with the figwheel-options.")}
          ;; TODO uncertain about this
          ["-fw" "--figwheel"]
          {:group :cljs.cli/compile :fn figwheel-opt
           :arg "bool"
           :doc (str "Use Figwheel to auto reload and report compile info. "
                     "Only takes effect when watching is happening and the "
                     "optimizations level is :none or nil."
                     "Defaults to true.")}
          ["-bb" "--background-build"]
          {:group :cljs.cli/compile :fn background-build-opt
           :arg "str"
           :doc "The name of a build config to watch and build in the background."}
          ["-pc" "--print-config"]
          {:group :cljs.cli/main&compile :fn print-config-opt
           :doc "Instead of running the command print out the configuration built up by the command. Useful for debugging."}
          }
   :main {["-b" "--build"]
          {:fn build-main-opt
           :arg "string"
           :doc (str "Run a compile. The supplied build name refers to a  "
                     "compililation options edn file. IE. \"dev\" will indicate "
                     "that a \"dev.cljs.edn\" will be read for "
                     "compilation options. The --build option will make an "
                     "extra attempt to "
                     "initialize a figwheel live reloading workflow. "
                     "If --repl follows, "
                     "will launch a REPL after the compile completes. "
                     "If --server follows, will start a web server according to "
                     "current configuration after the compile "
                     "completes.")}
          ["-bo" "--build-once"]
          {:fn build-once-main-opt
           :arg "string"
           :doc (str "Compile for the build name one time. "
                     "Looks for a build EDN file just like the --build command.")}
          ["-r" "--repl"]
          {:fn repl-main-opt
           :doc "Run a REPL"}
          ["-s" "--serve"]
          {:fn serve-main-opt
           :arg "host:port"
           :doc "Run a server based on the figwheel-main configuration options."}
          ["-h" "--help" "-?"]
          {:fn help-opt
           :doc "Print this help message and exit"}
          }})

;; ----------------------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------------------

(defn default-output-dir* [target & [scope]]
  (->> (cond-> [(or target default-target-dir) "public" "cljs-out"]
         scope (conj scope))
       (apply io/file)
       (.getPath)))

(defmulti default-output-dir (fn [{:keys [options]}]
                               (get options :target :browser)))

(defmethod default-output-dir :default [{:keys [::config ::build]}]
  (default-output-dir* (:target-dir config) (:id build)))

(defmethod default-output-dir :nodejs [{:keys [::config ::build]}]
  (let [target (:target-dir config default-target-dir)
        scope (:id build)]
    (->> (cond-> [target "node"]
           scope (conj scope))
         (apply io/file)
         (.getPath))))

(defn default-output-to* [target & [scope]]
  (.getPath (io/file (or target default-target-dir) "public" "cljs-out"
                     (cond->> "main.js"
                       scope (str scope "-")))))

(defmulti default-output-to (fn [{:keys [options]}]
                              (get options :target :browser)))

(defmethod default-output-to :default [{:keys [::config ::build]}]
  (default-output-to* (:target-dir config) (:id build)))

(defmethod default-output-to :nodejs [{:keys [::build] :as cfg}]
  (let [scope (:id build)]
    (.getPath (io/file (default-output-dir cfg)
                       (cond->> "main.js"
                         scope (str scope "-"))))))

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

(defn process-main-config [{:keys [ring-handler] :as main-config}]
  (let [handler (and ring-handler (fw-util/require-resolve-var ring-handler))]
    (when (and ring-handler (not handler))
      (throw (ex-info "Unable to find :ring-handler" {::error true
                                                      :ring-handler ring-handler})))
    (cond-> main-config
      handler (assoc :ring-handler handler))))

(defn process-figwheel-main-edn [main-edn]
  (when main-edn
    (when-not (false? (:validate-config main-edn))
      (log/info "Validating figwheel-main.edn")
      (validate-config!
       :figwheel.main.schema.config/edn
       main-edn "Configuration error in figwheel-main.edn"
       "figwheel-main.edn is valid!"))
    (process-main-config main-edn)))

;; use tools reader read-string for better error messages
#_(redn/read-string)
(defn fetch-figwheel-main-edn [cfg]
  (when (.isFile (io/file "figwheel-main.edn"))
    (read-edn-file "figwheel-main.edn")))

(defn- config-figwheel-main-edn [cfg]
  (let [config-edn (process-figwheel-main-edn
                    (or (::start-figwheel-options cfg)
                        (fetch-figwheel-main-edn cfg)))]
    (cond-> cfg
      config-edn (update ::config #(merge config-edn %)))))

(defn- config-merge-current-build-conf [{:keys [::extra-config ::build] :as cfg}]
  (update cfg
          ::config #(extra-config-merge
                     (merge-with (fn [a b] (if b b a)) %
                                 (process-main-config (:config build)))
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

(defn warn-that-dir-not-on-classpath [typ dir]
  (let [[n k] (condp = typ
                :source ["Source directory" :source-paths]
                :target ["Target directory" :resource-paths])]
    (log/warn (ansip/format-str
               [:yellow n " "
                (pr-str (str dir))
                " is not on the classpath"]))
    (log/warn "Please fix this by adding" (pr-str (str dir))
              "to your classpath\n"
              "I.E.\n"
              "For Clojure CLI Tools in your deps.edn file:\n"
              "   ensure" (pr-str (str dir))
              "is in your :paths key\n\n"
              (when k
                (format
                 (str "For Leiningen in your project.clj:\n"
                      "   add it to the %s key\n")
                 (pr-str k))))))

;; takes a string or file representation of a directory
(defn- add-classpath! [dir]
  (when-not (fw-util/dir-on-classpath? dir)
    (log/warn (ansip/format-str [:yellow
                                 (format "Attempting to dynamically add %s to classpath!"
                                 (pr-str (str dir)))]))
    (fw-util/add-classpath! (.toURL (io/file dir)))))

(defn- config-main-source-path-on-classpath [{:keys [options] :as cfg}]
  (when-let [main (:ns cfg)]
    (when-not (fw-util/safe-ns->location main)
      (when-let [src-dir (fw-util/find-source-dir-for-cljs-ns main)]
        (when-not (fw-util/dir-on-classpath? src-dir)
          (add-classpath! src-dir)
          (warn-that-dir-not-on-classpath :source src-dir)))))
  cfg)

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
               (let [ns-watch-dir (and
                                   (#{:repl :serve} (:mode config))
                                   (not (:watch options))
                                   (empty? %)
                                   (:main options)
                                   (watch-dir-from-ns (:main options)))]
                 (cond-> %
                   (:watch options) (conj (:watch options))
                   ns-watch-dir (conj ns-watch-dir)))))))

(defn- config-ensure-watch-dirs-on-classpath [{:keys [::config] :as cfg}]
  (doseq [src-dir (:watch-dirs config)]
    (when-not (fw-util/dir-on-current-classpath? src-dir)
      (add-classpath! src-dir)
      (warn-that-dir-not-on-classpath :source src-dir)))
  cfg)

;; needs local config
(defn figwheel-mode? [{:keys [::config options]}]
  (and (:figwheel-core config true)
       (and (#{:repl :serve} (:mode config))
            (not-empty (:watch-dirs config)))
       (= :none (:optimizations options :none))))

(defn repl-connection? [{:keys [::config options] :as cfg}]
  (or (and (#{:repl :main} (:mode config))
           (= :none (:optimizations options :none)))
      (figwheel-mode? cfg)))

;; TODO this is a no-op right now
(defn prep-client-config [config]
  (let [cl-config (select-keys config [])]
    cl-config))

;; targets options needs local config
(defn- config-figwheel-mode? [{:keys [::config options] :as cfg}]
  (cond-> cfg
    ;; check for a main??
    (figwheel-mode? cfg)
    (update-in [:options :preloads]
               (fn [p]
                 (vec (distinct
                       (concat p '[figwheel.core figwheel.main])))))
    (false? (:heads-up-display config))
    (update-in [:options :closure-defines] assoc 'figwheel.core/heads-up-display false)
    (true? (:load-warninged-code config))
    (update-in [:options :closure-defines] assoc 'figwheel.core/load-warninged-code true)))

;; targets options
;; TODO needs to consider case where one or the other is specified???
(defn- config-default-dirs [{:keys [options ::config ::build] :as cfg}]
  (cond-> cfg
    (nil? (:output-to options))
    (assoc-in [:options :output-to] (default-output-to cfg))
    (nil? (:output-dir options))
    (assoc-in [:options :output-dir] (default-output-dir cfg))))

(defn figure-default-asset-path [{:keys [figwheel-options options ::config ::build] :as cfg}]
  (if (= :nodejs (:target options))
    (:output-dir options)
    (let [{:keys [output-dir]} options]
      ;; TODO could discover the resource root if there is only one
      ;; or if ONLY static file serving can probably do something with that
      ;; as well
      ;; UNTIL THEN if you have configured your static resources no default asset-path
      (when-not (contains? (:ring-stack-options figwheel-options) :static)
        (let [parts (fw-util/relativized-path-parts (or output-dir
                                                        (default-output-dir cfg)))]
          (when-let [asset-path
                     (->> parts
                          (split-with (complement #{"public"}))
                          last
                          rest
                          not-empty)]
            (str (apply io/file asset-path))))))))

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
        conn? (repl-connection? cfg)]
    (cond-> cfg
      conn?
      (update-in [:options :closure-defines] assoc 'figwheel.repl/connect-url conn-url)
      conn?
      (update-in [:options :preloads]
                 (fn [p]
                   (vec (distinct
                         (concat p '[figwheel.repl.preload])))))
      conn?
      (update-in [:options :repl-requires] into '[[figwheel.main :refer-macros [stop-builds start-builds build-once reset clean status]]
                                                  [figwheel.repl :refer-macros [conns focus]]])
      (and conn? (:client-print-to config))
      (update-in [:options :closure-defines] assoc
                 'figwheel.repl/print-output
                 (string/join "," (distinct (map name (:client-print-to config)))))
      (and conn? (:client-log-level config))
      (update-in [:options :closure-defines] assoc
                 'figwheel.repl/client-log-level
                 (name (:client-log-level config)))
      (and conn? (not-empty (:watch-dirs config)))
      (update-in [:repl-options :analyze-path] (comp vec concat) (:watch-dirs config))
      (and conn? (not-empty connect-id))
      (assoc-in [:repl-env-options :connection-filter]
                (let [kys (keys connect-id)]
                  (fn [{:keys [query]}]
                    (= (select-keys query kys)
                       connect-id)))))))

(defn config-cljs-devtools [{:keys [::config options] :as cfg}]
  (if (and
       (nil? (:target options))
       (= :none (:optimizations options :none))
       (:cljs-devtools config true)
       (try (bapi/ns->location 'devtools.preload) (catch Throwable t false)))
    (update-in cfg
               [:options :preloads]
               (fn [p]
                 (vec (distinct
                       (concat p '[devtools.preload])))))
    cfg))

(defn config-open-file-command [{:keys [::config options] :as cfg}]
  (if-let [setup (and (:open-file-command config)
                      (repl-connection? cfg)
                      (fw-util/require-resolve-var 'figwheel.main.editor/setup))]
    (-> cfg
        (update ::initializers (fnil conj []) #(setup (:open-file-command config)))
        (update-in [:options :preloads]
                   (fn [p] (vec (distinct (conj p 'figwheel.main.editor))))))
    cfg))

(defn config-eval-back [{:keys [::config options] :as cfg}]
  (if-let [setup (and (repl-connection? cfg)
                      (fw-util/require-resolve-var 'figwheel.main.evalback/setup))]
    (-> cfg
        (update ::initializers (fnil conj []) #(setup))
        (update-in [:options :preloads]
                   (fn [p] (vec (distinct (concat p '[figwheel.main.evalback figwheel.main.testing]))))))
    cfg))

(defn watch-css [css-dirs]
  (when-let [css-dirs (not-empty css-dirs)]
    (when-let [start-css (fw-util/require-resolve-var 'figwheel.main.css-reload/start*)]
      (start-css css-dirs))))

(defn config-watch-css [{:keys [::config options] :as cfg}]
  (cond-> cfg
    (and (not-empty (:css-dirs config))
         (repl-connection? cfg))
    (->
     (update ::initializers (fnil conj []) #(watch-css (:css-dirs config)))
     (update-in [:options :preloads]
                (fn [p] (vec (distinct (conj p 'figwheel.main.css-reload))))))))

(defn get-repl-options [{:keys [options args inits repl-options] :as cfg}]
  (assoc (merge (dissoc options :main)
                repl-options)
         :inits
         (into
          [{:type :init-forms
            :forms (when-not (empty? args)
                     [`(set! *command-line-args* (list ~@args))])}]
          inits)))

(defn get-repl-env-options [{:keys [repl-env-options ::config options] :as cfg}]
  (let [repl-options (get-repl-options cfg)]
    (merge
     (select-keys config
                  [:ring-server
                   :ring-server-options
                   :ring-stack
                   :ring-stack-options
                   :ring-handler
                   :launch-node
                   :inspect-node
                   :node-command
                   :broadcast
                   :open-url
                   :repl-eval-timeout])
     repl-env-options ;; from command line
     (select-keys options [:output-to :output-dir :target]))))

(defn config-finalize-repl-options [cfg]
  (let [repl-options (get-repl-options cfg)
        repl-env-options (get-repl-env-options cfg)]
    (assoc cfg
           :repl-options repl-options
           :repl-env-options repl-env-options)))

(defn config-set-log-level! [{:keys [::config] :as cfg}]
  (when-let [log-level (:log-level config)]
    (log/set-level log-level))
  cfg)

(defn config-ansi-color-output! [{:keys [::config] :as cfg}]
  (when (some? (:ansi-color-output config))
    (alter-var-root #'ansip/*use-color* (fn [_] (:ansi-color-output config))))
  cfg)

(defn config-log-syntax-error-style! [{:keys [::config] :as cfg}]
  (when (some? (:log-syntax-error-style config))
    (alter-var-root #'log/*syntax-error-style* (fn [_] (:log-syntax-error-style config))))
  cfg)

(defn- config-warn-resource-directory-not-on-classpath [{:keys [::config options] :as cfg}]
  ;; this could check for other directories than resources
  ;; but this is mainly to help newcomers
  (when (and (nil? (:target options))
             (#{:repl :serve} (:mode config))
             (.isFile (io/file "resources/public/index.html"))
             (not (fw-util/dir-on-classpath? "resources")))
    (log/warn (ansip/format-str
               [:yellow "A \"resources/public/index.html\" exists but the \"resources\" directory is not on the classpath\n"
                "    the default server will not be able to find your index.html"])))
  cfg)

#_(config-connect-url {::build-name "dev"})

(defn update-config [cfg]
  (->> cfg
       config-figwheel-main-edn
       config-merge-current-build-conf
       config-ansi-color-output!
       config-set-log-level!
       config-log-syntax-error-style!
       config-repl-serve?
       config-main-ns
       config-main-source-path-on-classpath
       config-update-watch-dirs
       config-ensure-watch-dirs-on-classpath
       config-figwheel-mode?
       config-default-dirs
       config-default-asset-path
       config-default-aot-cache-false
       config-repl-connect
       config-cljs-devtools
       config-open-file-command
       config-eval-back
       config-watch-css
       config-finalize-repl-options
       config-clean
       config-warn-resource-directory-not-on-classpath))

;; ----------------------------------------------------------------------------
;; Main action
;; ----------------------------------------------------------------------------

(defn build [{:keys [watch-dirs mode ::build] :as config} options cenv]
  (let [id (:id (::build *config*) "dev")]
    ;; TODO should probably try obtain a watch path from :main here
    ;; if watch-dirs is empty
    (if-let [paths (and (not= mode :build-once)
                        (not-empty watch-dirs))]
      (do
        (build-cljs id (apply bapi/inputs paths) options cenv)
        (watch-build id paths options cenv (select-keys config [:reload-clj-files
                                                                :wait-time-ms
                                                                :hawk-options])))
      (let [source (when (:main options)
                     (:uri (fw-util/ns->location (symbol (:main options)))))]
        (cond
          source
          (build-cljs id source options cenv)
          ;; TODO need :compile-paths config param
          (not-empty watch-dirs)
          (build-cljs id (apply bapi/inputs watch-dirs) options cenv))))))

(defn log-server-start [repl-env]
  (let [host (get-in repl-env [:ring-server-options :host] "localhost")
        port (get-in repl-env [:ring-server-options :port] figwheel.repl/default-port)
        scheme (if (get-in repl-env [:ring-server-options :ssl?])
                 "https" "http")]
    (log/info (str "Starting Server at " scheme "://" host ":" port ))))

(defn start-file-logger []
  (when-let [log-fname (and (bound? #'*config*) (get-in *config* [::config :log-file]))]
    (log/info "Redirecting log ouput to file:" log-fname)
    (io/make-parents log-fname)
    (log/switch-to-file-handler! log-fname)))

;; ------------------------------
;; REPL
;; ------------------------------

(defn repl-api-docs []
  (let [dvars (filter (comp :cljs-repl-api meta) (vals (ns-publics 'figwheel.main)))]
    (string/join
     "\n"
     (map (fn [{:keys [ns name arglists doc]}]
            (str "--------------------------------------------------------------------------------\n"
             "(" ns "/" name
             (when-let [args (not-empty (first arglists))]
               (str " " (pr-str args)))
             ")\n   " doc))
          (map meta dvars)))))

#_(println (repl-api-docs))

(defn bound-var? [sym]
  (when-let [v (resolve sym)]
    (thread-bound? v)))

(defn in-nrepl? [] (bound-var? 'clojure.tools.nrepl.middleware.interruptible-eval/*msg*))

(defn nrepl-repl [repl-env repl-options]
  (if-let [piggie-repl (or (and (bound-var? 'cider.piggieback/*cljs-repl-env*)
                                (resolve 'cider.piggieback/cljs-repl))
                           (and (bound-var? 'cemerick.piggieback/*cljs-repl-env*)
                                (resolve 'cemerick.piggieback/cljs-repl)))]
    (apply piggie-repl repl-env (mapcat identity repl-options))
    (throw (ex-info "Failed to launch Figwheel CLJS REPL: nREPL connection found but unable to load piggieback.
This is commonly caused by
 A) not providing piggieback as a dependency and/or
 B) not adding piggieback middleware into your nrepl middleware chain.
Please see the documentation for piggieback here https://github.com/clojure-emacs/piggieback#installation

Note: Cider will inject this config into your project.clj.
This can cause confusion when your are not using Cider."
                    {::error :no-cljs-nrepl-middleware}))))

(defn repl-caught [err repl-env repl-options]
  (let [root-source-info (some-> err ex-data :root-source-info)]
    (if (and (instance? clojure.lang.IExceptionInfo err)
             (#{:js-eval-error :js-eval-exception} (:type (ex-data err))))
      (try
        (cljs.repl/repl-caught err repl-env repl-options)
        (catch Throwable e
          (let [{:keys [value stacktrace] :as data} (ex-data err)]
            (when value
              (println value))
            (when stacktrace
              (println stacktrace))
            (log/debug (with-out-str (pprint data))))))
      (let [except-data (fig-ex/add-excerpt (fig-ex/parse-exception err))]
        ;; TODO strange ANSI color error when printing this inside rebel-readline
        (println (binding [ansip/*use-color* (if (resolve 'rebel-readline.cljs.repl/repl*)
                                               false
                                               ansip/*use-color*)]
                   (ansip/format-str (log/format-ex except-data))))
        #_(clojure.pprint/pprint (Throwable->map err))
        (flush)))))

(def repl-header
  (str "Figwheel Main Controls:
          (figwheel.main/stop-builds id ...)  ;; stops Figwheel autobuilder for ids
          (figwheel.main/start-builds id ...) ;; starts autobuilder focused on ids
          (figwheel.main/reset)               ;; stops, cleans, reloads config, and starts autobuilder
          (figwheel.main/build-once id ...)   ;; builds source one time
          (figwheel.main/clean id ...)        ;; deletes compiled cljs target files
          (figwheel.main/status)              ;; displays current state of system
Figwheel REPL Controls:
          (figwheel.repl/conns)               ;; displays the current connections
          (figwheel.repl/focus session-name)  ;; choose which session name to focus on
In the cljs.user ns, controls can be called without ns ie. (conns) instead of (figwheel.repl/conns)
    Docs: (doc function-name-here)
    Exit: :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object"))

;; TODO this needs to work in nrepl as well
(defn repl [repl-env repl-options]
  (log-server-start repl-env)
  (log/info "Starting REPL")
  ;; when we have a logging file start log here
  (start-file-logger)
  (binding [cljs.analyzer/*cljs-warning-handlers*
            (conj (remove #{cljs.analyzer/default-warning-handler}
                          cljs.analyzer/*cljs-warning-handlers*)
                  (fn [warning-type env extra]
                    (when (get cljs.analyzer/*cljs-warnings* warning-type)
                      ;; warnings happen during compile so we must
                      ;; output to *err* but when rebel readline is
                      ;; available we will use the the root value of
                      ;; out which is bound to a special printer this
                      ;; is a bit tricky, its best just to handle
                      ;; *err* correctly in rebel-readline
                      (binding [*out*
                                (if (some-> (resolve 'rebel-readline.jline-api/*line-reader*)
                                            deref)
                                  (.getRawRoot #'*out*)
                                  *err*)]
                        (->> {:warning-type warning-type
                              :env env
                              :extra extra
                              :path ana/*cljs-file*}
                             figwheel.core/warning-info
                             (fig-ex/root-source->file-excerpt (:root-source-info env))
                             log/format-ex
                             ansip/format-str
                             string/trim-newline
                             println)
                        (flush)))))]
    (let [repl-options (-> repl-options
                           (assoc :caught (:caught repl-options repl-caught)))]
      (println (ansip/format-str
                [:bright (format "Prompt will show when REPL connects to evaluation environment (i.e. %s)"
                                 (if (= :nodejs (:target repl-options))
                                   "Node"
                                   "a REPL hosting webpage"))]))
      (println repl-header)

      (if (in-nrepl?)
        (nrepl-repl repl-env repl-options)
        (let [repl-fn (or (when-not (false? (:rebel-readline (::config *config*)))
                            (fw-util/require-resolve-var 'rebel-readline.cljs.repl/repl*))
                          cljs.repl/repl*)]
          (try
            (repl-fn repl-env repl-options)
            (catch clojure.lang.ExceptionInfo e
              (if (-> e ex-data :type (= :rebel-readline.jline-api/bad-terminal))
                (do (println (.getMessage e))
                    (cljs.repl/repl* repl-env repl-options))
                (throw e)))))))))

(defn serve [{:keys [repl-env repl-options eval-str join?]}]
  (log-server-start repl-env)
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
    (when (empty? (:watch-dirs config))
          (log/failure "Can not watch a build with no :watch-dirs"))
    (when (not-empty (:watch-dirs config))
      (log/info "Starting background autobuild - " (:id build))
      (binding [cljs.env/*compiler* cenv]
        (build-cljs (:id build) (apply bapi/inputs (:watch-dirs config)) (:options cfg) cenv)
        (watch-build (:id build)
                     (:watch-dirs config)
                     (:options cfg)
                     cenv
                     (select-keys config [:reload-clj-files :wait-time-ms]))
        ;; TODO need to move to this pattern instead of repl evals
        (when (first (filter #{'figwheel.core} (:preloads (:options cfg))))
          (binding [cljs.repl/*repl-env* (figwheel.repl/repl-env*
                                          (select-keys repl-env-options
                                                       [:connection-filter]))
                    figwheel.core/*config*
                    (select-keys config [:hot-reload-cljs
                                         :broadcast-reload
                                         :reload-dependents])]
            (figwheel.core/start*)))))))

(defn start-background-builds [{:keys [::background-builds] :as cfg}]
  (doseq [build background-builds]
    (background-build cfg build)))

(defn validate-fix-target-classpath! [{:keys [::config ::build options]}]
  (when (nil? (:target options)) ;; browsers need the target classpath to load files
    (when-not (contains? (:ring-stack-options config) :static)
      (when-let [output-to (:output-to options)]
        (when-not (.isAbsolute (io/file output-to))
          (let [parts (fw-util/path-parts output-to)
                target-dir (first (split-with (complement #{"public"}) parts))]
            (when (some #{"public"} parts)
              (when-not (empty? target-dir)
                (let [target-dir (apply io/file target-dir)]
                  (if (and (fw-util/dir-on-classpath? target-dir)
                           (not (.exists target-dir)))
                    ;; quietly fix this situation??
                    (do
                      (log/warn
                       (ansip/format-str
                        [:yellow
                         "Making target directory "
                         (pr-str (str target-dir))
                         " and re-adding it to the classpath"
                         " (this only needs to be done when the target directory doesn't exist)"]))
                      (.mkdirs target-dir)
                      (fw-util/add-classpath! (.toURL target-dir)))
                    (when-not (fw-util/dir-on-classpath? target-dir)
                      (.mkdirs target-dir)
                      (add-classpath! target-dir)
                      (warn-that-dir-not-on-classpath :target target-dir))))))))))))

;; build-id situations
;; - temp-dir build id doesn't matter
;; - target directory build id
;;   (if unique would ensure clean main compile and run)
;;   when don't you want it to be unique
;;   when do you not want a clean compile when running main or repl?
;;     (when you are running it over and over again)
;;     for main we can use the main-ns for a build id
;;   - repl only
;;   - main only

(defn default-main [repl-env-fn cfg]
  (let [cfg (if (should-add-temp-dir? cfg)
              (add-temp-dir cfg)
              cfg)
        cfg (if (get-in cfg [::build :id])
              cfg
              (assoc-in cfg [::build :id] "figwheel-main-option-build"))
        output-to (get-in cfg [:options :output-to]
                          (default-output-to cfg))
        main (:main cfg)
        cfg (-> cfg
                (assoc-in [:options :aot-cache] false)
                (update :options #(assoc % :main
                                         (or (some-> (:main cfg) symbol)
                                             'figwheel.repl.preload)))
                (assoc-in [::config
                           :ring-stack-options
                           :figwheel.server.ring/dev
                           :figwheel.server.ring/system-app-handler]
                          ;; executing a function is slightly different as well
                          #(helper/middleware
                            %
                            {:header "Main fn exec page"
                             :body (str
                                    (format "<blockquote class=\"action-box\">Invoked main function: <code>%s/-main</code></blockquote>" (str main))
                                    (slurp
                                     (io/resource "public/com/bhauman/figwheel/helper/content/welcome_main_exec.html")))
                             :output-to output-to}))
                (assoc-in [::config :mode] :repl))
        source (:uri (fw-util/ns->location (get-in cfg [:options :main])))]
    (let [{:keys [options repl-options repl-env-options ::config] :as b-cfg}
          (update-config cfg)
          {:keys [pprint-config]} config]
      (if pprint-config
        (do
          (log/info ":pprint-config true - printing config:")
          (print-conf b-cfg))
        (cljs.env/ensure
         (build-cljs (get-in b-cfg [::build :id] "figwheel-main-option-build")
                     source
                     (:options b-cfg) cljs.env/*compiler*)
          (cljs.cli/default-main repl-env-fn b-cfg))))))

(defn add-default-system-app-handler [cfg]
  (update-in
   cfg
   [:repl-env-options
    :ring-stack-options
    :figwheel.server.ring/dev
    :figwheel.server.ring/system-app-handler]
   (fn [sah]
     (if sah
       sah
       #(helper/missing-index % (select-keys (:options cfg) [:output-to]))))))

(defn default-compile [repl-env-fn cfg]
  (let [{:keys [options repl-options repl-env-options ::config] :as b-cfg}
        (add-default-system-app-handler (update-config cfg))
        {:keys [mode pprint-config]} config
        repl-env (apply repl-env-fn (mapcat identity repl-env-options))
        cenv (cljs.env/default-compiler-env options)]
    (validate-fix-target-classpath! b-cfg)
    (binding [*base-config* cfg
              *config* b-cfg]
      (cljs.env/with-compiler-env cenv
        (log/trace "Verbose config:" (with-out-str (pprint b-cfg)))
        (if pprint-config
          (do
            (log/info ":pprint-config true - printing config:")
            (print-conf b-cfg))
          (binding [cljs.repl/*repl-env* repl-env
                    figwheel.core/*config* (select-keys config [:hot-reload-cljs
                                                                :broadcast-reload
                                                                :reload-dependents])]
            (try
              (let [fw-mode? (figwheel-mode? b-cfg)]
                (build config options cenv)
                (when-not (= mode :build-once)
                  (start-background-builds (assoc cfg
                                                  ::start-figwheel-options
                                                  config))
                  (doseq [init-fn (::initializers b-cfg)] (init-fn))
                  (log/trace "Figwheel.core config:" (pr-str figwheel.core/*config*))
                  (figwheel.core/start*)
                  (cond
                    (= mode :repl)
                    ;; this forwards command line args
                    (repl repl-env repl-options)
                    (= mode :serve)
                    ;; we need to get the server host:port args
                    (serve {:repl-env repl-env
                            :repl-options repl-options
                          :join? (get b-cfg ::join-server? true)})
                    ;; the final case is compiling without a repl or a server
                    ;; if there is a watcher running join it
                    (and (fww/running?) (get b-cfg ::join-server? true))
                    (fww/join))))
              (finally
                (when (get b-cfg ::join-server? true)
                  (fww/stop!))))))))))

(defn start-build-arg->build-options [build]
  (let [[build-id build-options config]
        (if (map? build)
          [(:id build) (:options build)
           (:config build)]
          [build])
        build-id (name build-id)
        options  (or (and (not build-options)
                          (get-build build-id))
                     build-options
                     {})
        config  (or config (meta options))]
    (cond-> {:id build-id
             :options options}
      config (assoc :config config))))

(defn start*
  ([join-server? build] (start* nil nil build))
  ([join-server? figwheel-options build & background-builds]
   (assert build "Figwheel Start: build argument required")
   (let [{:keys [id] :as build} (start-build-arg->build-options build)
         cfg
         (cond-> {:options (:options build)
                  ::join-server? (if (true? join-server?) true false)}
           figwheel-options (assoc ::start-figwheel-options figwheel-options)
           id    (assoc ::build (dissoc build :options))
           (not (get figwheel-options :mode))
           (assoc-in [::config :mode] :repl)
           (not-empty background-builds)
           (assoc ::background-builds (mapv
                                       start-build-arg->build-options
                                       background-builds)))]
     (default-compile cljs.repl.figwheel/repl-env cfg))))

(defn start
  "Starts Figwheel.

  Example:

  (start \"dev\") ;; will look up the configuration from figwheel-main.edn
                  ;; and dev.cljs.edn

  With inline build config:
  (start {:id \"dev\" :options {:main 'example.core}})

  With inline figwheel config:
  (start {:css-dirs [\"resources/public/css\"]} \"dev\")

  With inline figwheel and build config:
  (start {:css-dirs [\"resources/public/css\"]}
         {:id \"dev\" :options {:main 'example.core}})

  If you don't want to launch a REPL:
  (start {:css-dirs [\"resources/public/css\"]
          :mode :serve}
         {:id \"dev\" :options {:main 'example.core}})"
  [& args]
  (apply start* false args))

(defn start-join
  "Starts figwheel and blocks, useful when starting figwheel as a
  server only i.e. `:mode :serve`  from a script."
  [& args]
  (apply start* true args))

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
      (when-let [options (-> watch' ::watch-info :options)]
        (println "Cleaning build id:" (-> watch' ::watch-info :id))
        (clean-build options)))))

(defmacro ^:cljs-repl-api clean
  "Takes one or more builds ids and deletes their compiled artifacts."
  [& build-ids]
  (clean* (map name build-ids))
  nil)

(defn status* []
  (println "------- Figwheel Main Status -------")
  (if-let [ids (not-empty (currently-watched-ids))]
    (println "Currently building:" (string/join ", " ids))
    (println "No builds are currently being built.")))

(defmacro ^:cljs-repl-api status
  "Displays the build ids of the builds are currently being watched and compiled."
  []
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
(defmacro ^:cljs-repl-api stop-builds
  "Takes one or more build ids and stops watching and compiling them."
  [& build-ids]
  (stop-builds* build-ids)
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
      (when main-build-id
        (let [{:keys [options repl-env-options ::config]} *config*
              {:keys [watch-dirs]} config]
          (println "Starting build id:" main-build-id)
          (bapi/build (apply bapi/inputs watch-dirs) options cljs.env/*compiler*)
          (watch-build main-build-id
                       watch-dirs options
                       cljs.env/*compiler*
                       (select-keys config [:reload-clj-files :wait-time-ms]))
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
(defmacro ^:cljs-repl-api start-builds
  "Takes one or more build names and starts them building."
  [& build-ids]
  (start-builds* build-ids)
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

(defmacro ^:cljs-repl-api reset
  "If no args are provided, all current builds will be cleaned and restarted.
   Otherwise, this will clean and restart the provided build ids."
  [& build-ids]
  (reset* build-ids))

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
                                          (:uri (fw-util/ns->location (symbol (:main options)))))]
                        source))]
          (when input
            (build-cljs i input options
                         (cljs.env/default-compiler-env options))))))))

(defmacro ^:cljs-repl-api build-once
  "Forces a single compile of the provided build ids."
  [& build-ids]
  (build-once* build-ids)
  nil)

;; ----------------------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------------------

(defn fix-simple-bool-arg* [flags args]
  (let [[pre post] (split-with (complement flags) args)]
    (if (empty? post)
      pre
      (concat pre [(first post) "true"] (rest post)))))

(defn fix-simple-bool-args [flags args]
  (reverse
   (reduce (fn [accum arg]
             (if (and (flags (first accum))
                      (not (#{"true" "false"} arg)))
               (-> accum
                   (conj "true")
                   (conj arg))
               (conj accum arg)))
           (list)
           args)))

(defn -main [& orig-args]
  ;; todo make this local with-redefs?
  (alter-var-root #'cli/default-commands cli/add-commands figwheel-commands)
  ;; set log level early
  (when-let [level (get-edn-file-key "figwheel-main.edn" :log-level)]
    (log/set-level level))
  (try
    (let [args       (fix-simple-bool-args #{"-pc" "--print-config"} orig-args)
          [pre post] (split-with (complement #{"-re" "--repl-env"}) args)
          _          (when (not-empty post)
                       (throw
                        (ex-info (str "figwheel.main does not support the --repl-env option\n"
                                      "The figwheel REPL is implicitly used.\n"
                                      "Perhaps you were intending to use the --target option?")
                                 {::error true})))
          _          (validate-cli! (vec orig-args))
          args'      (concat ["-re" "figwheel"] args)
          args'      (if (or (empty? args)
                             (= args ["-pc" "true"])
                             (= args ["--print-config" "true"]))
                       (concat args' ["-r"]) args')]
      (with-redefs [cljs.cli/default-compile default-compile
                    cljs.cli/load-edn-opts load-edn-opts]
        (apply cljs.main/-main args')))
    (catch Throwable e
      (let [d (ex-data e)]
        (if (or (:figwheel.main.schema.core/error d)
                (:figwheel.main.schema.cli/error d)
                (:cljs.main/error d)
                (::error d))
          (binding [*out* *err*]
            (println (.getMessage e)))
          (throw e))))))))

#_(def test-args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
