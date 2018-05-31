(ns figwheel.main.schema.cli
  (:require
   [cljs.build.api :as bapi]
   [cljs.cli]
   [cljs.util]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [expound.alpha :as exp]
   [expound.printer :as printer]
   [figwheel.main.schema.core :refer [def-spec-meta
                                      non-blank-string?
                                      file-exists?
                                      directory-exists?]]
   [figwheel.main.util :as fw-util]
   [spell-spec.alpha :as spell]
   [spell-spec.expound :as spell-exp]))

(defn flag-arg? [s]
  (and (non-blank-string? s)
       (.startsWith s "-")))

(defn not-flag? [x]
  (and (string? x)
       (not (flag-arg? x))))

(defn inline-edn-string? [s]
  (or (string/starts-with? s "{")
      (string/starts-with? s "^")))

(defn resource-exists? [f]
  (when-let [rname (cond
                     (string/starts-with? f "@/") (subs f 2)
                     (string/starts-with? f "@")  (subs f 1)
                     :else nil)]
    (io/resource rname)))


(defn file-or-resource-should-exist [f]
  (if-let [rname (cond
                   (string/starts-with? f "@/") (subs f 2)
                   (string/starts-with? f "@")  (subs f 1)
                   :else nil)]
    (io/resource rname)
    (file-exists? f)))

(defn all-files-and-resources-should-exist [s]
  (every? file-or-resource-should-exist (cljs.util/split-paths s)))

(defn readable? [s]
  (try
    (read-string s)
    true
    (catch Throwable t
      false)))

(exp/def ::inline-edn-string inline-edn-string?
  "should be an inline edn string that starts with {")

(exp/def ::all-files-and-resources-should-exist all-files-and-resources-should-exist
  "should be one of more existing files or resources separated by :\n
resource paths should start with @")

(s/def ::edn-option
  (s/or :inline-edn-string ::inline-edn-string
        :list-of-existing-edn-resources ::all-files-and-resources-should-exist))

(s/def ::compile-opts (s/cat :opt-key #{"-co" "--compile-opts"}
                             :opt-val ::edn-option))

(s/def ::repl-opts     (s/cat :opt-key #{"-ro" "--repl-opts"}
                              :opt-val ::edn-option))

(s/def ::figwheel-opts (s/cat :opt-key #{"-fwo" "--fw-opts"}
                              :opt-val ::edn-option))

(s/def ::output-dir (s/cat :opt-key #{"-d" "--output-dir"}
                           :opt-val not-flag?))

(s/def ::target (s/cat :opt-key #{"-t" "--target"}
                       :opt-val #{"node" "nodejs" "nashorn" "webworker" "none"}))

(defn resource-path? [f]
  (string/starts-with? f "@"))

(exp/def ::resource-exists resource-exists?
  "should be an existing resource that starts with @")

(exp/def ::file-exists file-exists?
  "should be an existing file")

(s/def ::file-or-resource-should-exist
  (s/or :resource ::resource-exists
        :file     (s/and (complement resource-path?) ::file-exists)))

(s/def ::init (s/cat :opt-key #{"-i" "--init"}
                     :opt-val ::file-or-resource-should-exist))

(exp/def ::readable readable? "should be a readable Clojure expression")

(s/def ::eval (s/cat :opt-key #{"-e" "--eval"}
                     :opt-val ::readable))

(s/def ::verbose (s/cat :opt-key #{"-v" "--verbose"}
                        :opt-val #{"true" "false"}))

(s/def ::optimizations (s/cat :opt-key #{"-O" "--optimizations"}
                              :opt-val #{"none" "whitespace" "simple" "advanced"}))

(defn build-exists? [b]
  (file-exists? (str b ".cljs.edn")))

(exp/def ::not-end-with-cljs-edn #(not (string/ends-with? % ".cljs.edn"))
  "should not end with .cljs.edn as this is already implied")

(exp/def ::build-exists build-exists?
  "should be a build file in the current directory that ends with .cljs.edn")

(exp/def ::directory-exists directory-exists?
  "should be an existing directory relative to the current directory")

(s/def ::build-name (s/and ::not-end-with-cljs-edn
                           ::build-exists))

(s/def ::background-build
  (s/cat :opt-key #{"-bb" "--background-build"}
         :opt-val ::build-name))

#_(validate-cli ["-bb" "devv"])

(s/def ::figwheel (s/cat :opt-key #{"-fw" "--figwheel"}
                         :opt-val #{"true" "false"}))

(s/def ::output-to (s/cat :opt-key #{"-o" "--output-to"}
                          :opt-val not-flag?))

(s/def ::print-config #{"-pc" "--print-config"})

(s/def ::watch (s/cat :opt-key #{"-w" "--watch"}
                      :opt-val ::directory-exists))

(defn integer-string? [s] (re-matches #"\d+" s))

(exp/def ::integer-string integer-string? "should be an integer")

(s/def ::port (s/cat :opt-key #{"-p" "--port"}
                     :opt-val ::integer-string))

(s/def ::host (s/cat :opt-key #{"-H" "--host"}
                     :opt-val not-flag?))

(defn can-require-and-resolve-var? [var-str]
  (boolean (fw-util/require-resolve-var var-str)))

(exp/def ::require-and-resolve-var can-require-and-resolve-var?
  "should be an existing var in a namespace that can be required")

(exp/def ::var-with-forward-slash #(re-matches #"[^\/]+\/[^\/]+" %)
  "should represent a var with a namespace and var name separated by a /")

(s/def ::ring-handler (s/cat :opt-key #{"-rh" "--ring-handler"}
                             :opt-val (s/and ::var-with-forward-slash
                                             ::require-and-resolve-var)))

(s/def ::init-opts
  (s/alt
   :compile-opts  ::compile-opts
   :output-dir    ::output-dir
   :repl-opts     ::repl-opts
   :figwheel-opts ::figwheel-opts
   :target        ::target
   :init          ::init
   :eval          ::eval
   :verbose       ::verbose
   :optimizations ::optimizations
   :background-build ::background-build
   :figwheel      ::figwheel
   :output-to     ::output-to
   :print-config  ::print-config
   :watch         ::watch
   :port          ::port
   :host          ::host
   :ring-handler  ::ring-handler))


;; TODO

(s/def ::script (s/cat :script-name ::file-exists
                       :args (s/* any?)))

;; if there is extra input after the presense of a main option
;; these are extra args that are going to be ignored
#_(validate-cli ["-e" "(list)" "-" "-i"])

#_(s/conform ::cli-options ["-e" "(list)" "-s" "lproject.clj"])


;; if there is extra input before a main option then it is an unknown flag
#_ (validate-cli ["-e" "(list)" "-asdf" "-r"])

(s/def ::stdin  #{"-"})

(defn host-port? [s] (string/includes? s ":"))

(exp/def ::host-port host-port?
  "should specify a host and port separated by a \":\" (ie. localhost:3000)")

(s/def ::repl  (s/cat :flag #{"-r" "--repl"}
                      :args (s/* any?)))

(s/def ::serve (s/cat :serve-opt #{"-s" "--serve"}
                      :serve-param (s/? ::host-port)))

(s/def ::repl-or-serve (s/alt :repl ::repl
                              :serve ::serve))

(s/def ::build  (s/cat :build-opt #{"-b" "--build"}
                       :build-val ::build-name))

(s/def ::build-once  (s/cat :build-opt #{"-bo" "--build-once"}
                            :build-val ::build-name))

(defn cljs-namespace-on-classpath? [ns]
  (try (bapi/ns->location ns)
       (catch Throwable t
         false)))

(exp/def ::cljs-namespace-on-classpath cljs-namespace-on-classpath?
  "should be a CLJS namespace on the classpath (.ie example.main)")

(s/def ::compile (s/cat :flag #{"-c" "--compile"}
                        :val  (s/? not-flag?)))

#_(s/explain-data ::cli-options ["-e" "(list)" "-c" "-figwheel" "-r"])

#_(validate-cli ["-e" "(list)" "asdf" "-c" "-asdf" "-r"])

#_(s/conform ::cli-options ["-e" "(list)"  "-c" "-figwheel" "-r"])

(s/def ::main (s/cat :flag #{"-m" "--main"}
                     :val  ::cljs-namespace-on-classpath
                     :args (s/* any?)))

(s/def ::help  #{"-h" "--help" "-?"})

(s/def ::main-opts (s/alt
                    :stdin  ::stdin
                    :script ::script
                    :build    (s/cat :opt ::build
                                     :repl-serve (s/? ::repl-or-serve))
                    :compile-ns (s/cat :flag #{"-c" "--compile"}
                                       :ns ::cljs-namespace-on-classpath
                                       :repl-serve (s/? ::repl-or-serve))
                    :compile    (s/cat :flag #{"-c" "--compile"}
                                       :repl-serve (s/? ::repl-or-serve))
                    :build-once ::build-once
                    :help   ::help
                    :repl   ::repl
                    :serve  ::serve
                    :main   ::main))

(s/def ::cli-options (s/cat :inits (s/* ::init-opts)
                            :mains (s/? ::main-opts)))

;; ----------------------------------------------------------------------
;; extra problem detection
;; ----------------------------------------------------------------------

(defn position-of-first-main-arg [args]
  (let [main-args (:main-dispatch cljs.cli/default-commands)]
    (when (some main-args args)
      (count (take-while (complement main-args) args)))))

(defn get-first-main-arg [args]
  (when-let [pos (position-of-first-main-arg args)]
    (nth args pos)))

(defn extra-input? [{:keys [reason]}] (= reason "Extra input"))

(defn error-pos [{:keys [in]}] (first in))

(defn error-before-main-flag? [{:keys [::s/value] :as expd} prob]
  (when-let [pos (error-pos prob)]
    (if-let [main-pos (position-of-first-main-arg value)]
      (< pos main-pos)
      true)))

(defn error-after-main-flag? [{:keys [::s/value] :as expd} prob]
  (when-let [pos (error-pos prob)]
    (when-let [main-pos (position-of-first-main-arg value)]
      (> pos main-pos))))

;; ----------------------------------------------------------------------
;; extra input before a main option
;; ----------------------------------------------------------------------

(defn unknown-flag? [expd p]
  (and (extra-input? p)
       (error-before-main-flag? expd p)
       (flag-arg? (-> expd ::s/problems first :val first))))

#_(let [expd (s/explain-data ::cli-options ["-e" "(list)" "-asdf"])]
    (unknown-flag? expd (first (::s/problems expd))))

;; specific case when an extra arg is mistaken for a script
(defn unknown-script-input? [{:keys [::s/value ::s/problems] :as expd} p]
  (and (extra-input? p)
       (error-before-main-flag? expd p)
       (when-let [arg (some-> p :val first)]
         (and (not (flag-arg? arg))
              (not (file-exists? arg))))))

#_(let [expd (s/explain-data ::cli-options ["-e" "(list)" "ee" "-r"])]
    (unknown-script-input? expd (first (::s/problems expd))))

;; ----------------------------------------------------------------------
;; extra input before after main option
;; ----------------------------------------------------------------------

(defn ignored-args? [expd p]
  (and (extra-input? p)
       (error-after-main-flag? expd p)))

#_(let [expd (s/explain-data ::cli-options ["-e" "(list)" "-c" "-s" "asdf:asdf" "-d"])]
    (ignored-args? expd (first (::s/problems expd))))

(defn problem-type [expd p]
  (cond
    (unknown-flag? expd p)
    ::unknown-flag
    (unknown-script-input? expd p)
    ::unknown-script-input
    (ignored-args? expd p)
    ::ignored-args
    :else nil))

(defn add-problem-type [expd p]
  (if-let [t (problem-type expd p)]
    (assoc p :expound.spec.problem/type t)
    p))

(defn add-problem-types [expd]
  (update expd ::s/problems #(mapv (partial add-problem-type expd) %)))

#_(remove-ns 'figwheel.main.schema.cli)

(defn similar-flags [f]
  (let [all-flags (filter some?
                          (concat (keys (:main-dispatch cljs.cli/default-commands))
                                  (keys (:init-dispatch cljs.cli/default-commands))))]
    (not-empty
     (cond
       (string/starts-with? f "--")
       ((spell/likely-misspelled (into #{}
                                       (filter #(string/starts-with? % "--"))
                                       all-flags))
        f)
       (and (string/starts-with? f "-") (> (count f) 4))
       ((spell/likely-misspelled (into #{}
                                       (filter #(string/starts-with? % "--"))
                                       all-flags))
          (str "-" f))
       :else
       (binding [spell/*length->threshold* (fn [_] 1)]
         ((spell/likely-misspelled (into #{}
                                         (comp
                                          (filter #(> (count %) 2))
                                          (filter #(re-matches #"-[^-].*" %)))
                                         all-flags))
          f))))))

(defmulti update-problem (fn [expd p] (:expound.spec.problem/type p)))

(defmethod update-problem :default [expd p] p)

(defmethod update-problem ::unknown-flag [{:keys [::s/value ::s/problems] :as expd} p]
  (let [unknown-flag (-> problems first :val first)]
    (if-let [similar (similar-flags unknown-flag)]
      (assoc p
             :expound.spec.problem/type ::misspelled-flag
             ::similar-flags similar
             ::misspelled-flag unknown-flag)
      (assoc p ::unknown-flag unknown-flag))))

(defmethod update-problem ::unknown-script-input [{:keys [::s/value ::s/problems] :as expd} p]
  (let [unknown-script (-> p :val first)]
    (assoc p ::unknown-script unknown-script)))

(defn update-problems [expd]
  (update expd ::s/problems #(mapv (partial update-problem expd) %)))

;; todo

;; - add docs with custom printer

;; - do some post checks to ensure that folks are not suppling init args
;; that are not needed for the given main arg

(defn doc-for-flag [val {:keys [in :expound.spec.problem/type ::similar-flags]}]
  (when-let [flag (cond (not-empty similar-flags)
                        (first similar-flags)
                        (let [pos (first in)]
                          (and (integer? pos) (not (zero? pos))))
                        (nth val (dec (first in))))]
    (first (filter
            (fn [[kys v]]
              ((set kys) flag))
            (apply concat ((juxt :main :init) cljs.cli/default-commands))))))

(let [expected-str (deref #'exp/expected-str)]
  (defn expected-str-with-doc [_type spec-name val path problems opts]
    (str (expected-str _type spec-name val path problems opts)
         (when-let [[flags {:keys [doc]}] (doc-for-flag val (first problems))]
           (when doc
             (str
              "\n\n__ Doc for " (string/join " " flags)  " _____\n\n"
             (printer/indent (string/join "\n" (#'cljs.cli/auto-fill doc 65)))))))))

(defn validate-cli [cli-options]
  (when-let [data (update-problems
                   (add-problem-types
                    (s/explain-data ::cli-options cli-options)))]
    #_(clojure.pprint/pprint data)
    (with-redefs [exp/expected-str expected-str-with-doc]
      (with-out-str
        ((exp/custom-printer {:print-specs? false
                              :show-valid-values? true})
         data)))))

(defn validate-cli! [cli-args context-msg]
  (if-let [explained (validate-cli cli-args)]
    (throw (ex-info (str context-msg "\n" explained)
                    {::error explained}))
    true))

#_(validate-cli! ["-b" "(list"] nil)

(defmethod exp/problem-group-str ::unknown-flag [_type spec-name val path problems opts]
  (spell-exp/exp-formated "Unknown CLI flag"  _type spec-name val path problems opts))

(defmethod exp/expected-str ::unknown-flag [_type spec-name val path problems opts]
  (let [{:keys [::unknown-flag]} (first problems)]
    (str "should be a known CLI flag (use figwheel.main --help to see available options)")))

#_(validate-cli ["-e" "(list)" "-ii" "project"  "-asdf"])

(defmethod exp/problem-group-str ::misspelled-flag [_type spec-name val path problems opts]
  (spell-exp/exp-formated "Misspelled CLI flag"  _type spec-name val path problems opts))

(defmethod exp/expected-str ::misspelled-flag [_type spec-name val path problems opts]
  (let [{:keys [::similar-flags]} (first problems)]
    (str "should probably be" (spell-exp/format-correction-list similar-flags))))

#_(validate-cli ["-e" "(list)" "-print-confi"  "-asdf"])

(defmethod exp/problem-group-str ::unknown-script-input [_type spec-name val path problems opts]
  (spell-exp/exp-formated "Script not found"  _type spec-name val path problems opts))

(defmethod exp/expected-str ::unknown-script-input [_type spec-name val path problems opts]
  (let [{:keys [::unknown-script]} (first problems)]
    (str "is being interpreted as the name of a script to exec but "
         unknown-script
         " is not a file")))

#_ (validate-cli ["-e" "(list)" "ee" "-r"])

(defmethod exp/problem-group-str ::ignored-args [_type spec-name val path problems opts]
  (spell-exp/exp-formated "Ignored Extra CLI arguments"  _type spec-name val path problems opts))

(defmethod exp/expected-str ::ignored-args [_type spec-name val path problems opts]
  (str "extra args are only allowed after the --repl, --main, - (stdin) or script args"))

#_ (validate-cli ["-e" "(list)" "-c" "-s" "asdf:asdf" "-d"])

#_(exp/expound-str ::cli-options ["-i" "asdf" "-i" "asdf" "-e" "15"])


;; for reference
#_"init options:
  -co, --compile-opts edn     Options to configure the build, can be an EDN
                              string or system-dependent path-separated list of
                              EDN files / classpath resources. Options will be
                              merged left to right. Any meta data will be
                              merged with the figwheel-options.
   -d, --output-dir path      Set the output directory to use
  -ro, --repl-opts edn        Options to configure the repl-env, can be an EDN
                              string or system-dependent path-separated list of
                              EDN files / classpath resources. Options will be
                              merged left to right.
   -t, --target name          The JavaScript target. Configures environment
                              bootstrap and defaults to browser. Supported
                              values: node or nodejs, nashorn, webworker, none

init options only for --main and --repl:
   -e, --eval string          Evaluate expressions in string; print non-nil
                              values
   -i, --init path            Load a file or resource
   -v, --verbose bool         If true, will enable ClojureScript verbose logging

init options only for --compile:
   -O, --optimizations level  Set optimization level, only effective with --
                              compile main option. Valid values are: none,
                              whitespace, simple, advanced
  -bb, --background-build str The name of a build config to watch and build in
                              the background.
  -fw, --figwheel bool        Use Figwheel to auto reload and report compile
                              info. Only takes effect when watching is
                              happening and the optimizations level is :none or
                              nil.Defaults to true.
 -fwo, --fw-opts edn          Options to configure figwheel.main, can be an EDN
                              string or system-dependent path-separated list of
                              EDN files / classpath resources. Options will be
                              merged left to right.
   -o, --output-to file       Set the output compiled file
  -pc, --print-config         Instead of running the command print out the
                              configuration built up by the command. Useful for
                              debugging.
   -w, --watch path           Continuously build, only effective with the --
                              compile and --build main options. This option can
                              be supplied multiple times.

Figwheel REPL options:
   -H, --host address         Address to bind
   -p, --port number          Port to bind
  -rh, --ring-handler string  Ring Handler for default REPL server EX. \"example.
                              server/handler\"

main options:
   -                          Run a script from standard input
   -b, --build string         Run a compile. The supplied build name refers to
                              a  compililation options edn file. IE. \"dev\" will
                              indicate that a \"dev.cljs.edn\" will be read for
                              compilation options. The --build option will make
                              an extra attempt to initialize a figwheel live
                              reloading workflow. If --repl follows, will
                              launch a REPL after the compile completes. If --
                              server follows, will start a web server according
                              to current configuration after the compile
                              completes.
  -bo, --build-once string    Compile for the build name one time. Looks for a
                              build EDN file just like the --build command.
   -c, --compile [ns]         Run a compile. If optional namespace specified,
                              use as the main entry point. If --repl follows,
                              will launch a REPL after the compile completes.
                              If --server follows, will start a web server that
                              serves the current directory after the compile
                              completes.
   -h, --help, -?             Print this help message and exit
   -m, --main ns              Call the -main function from a namespace with args
   -r, --repl                 Run a REPL
   -s, --serve host:port      Run a server based on the figwheel-main
                              configuration options.
   path                       Run a script from a file or resource"
