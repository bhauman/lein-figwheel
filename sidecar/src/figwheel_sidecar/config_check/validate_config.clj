(ns figwheel-sidecar.config-check.validate-config
  (:require
   [figwheel-sidecar.config-check.ansi :refer [color]]
   [figwheel-sidecar.config-check.type-check :refer [spec or-spec ref-schema named? anything?
                                        doc
                                        requires-keys
                                        string-or-symbol?
                                        type-checker type-check print-one-error index-spec with-schema] :as tc]
   [fipp.engine :refer [pprint-document]]
   [clojure.string :as string]
   [clojure.java.io :as io]))

(defn fmt-doc
  ([s] s)
  ([s ex]
   [:group (fmt-doc s)
    :line
    :line
    [:nest 2 ex] :line]))

(def CompilerWarningsSpec
  (->>
   [:undeclared-ns-form
    :protocol-deprecated
    :undeclared-protocol-symbol
    :fn-var
    :invalid-arithmetic
    :preamble-missing
    :undeclared-var
    :protocol-invalid-method
    :variadic-max-arity
    :multiple-variadic-overloads
    :fn-deprecated
    :redef
    :fn-arity
    :invalid-protocol-symbol
    :dynamic
    :undeclared-ns
    :overload-arity
    :extending-base-js-type
    :single-segment-namespace
    :protocol-duped-method
    :protocol-multiple-impls
    :invoke-ctor]
   (map #(vector % (ref-schema 'Boolean)))
   (into {})))

(def ClosureWarningsSpec
  (->> [:access-controls
        :ambiguous-function-decl
        :debugger-statement-present
        :check-regexp
        :check-types
        :check-useless-code
        :check-variables
        :const
        :constant-property
        :deprecated
        :duplicate-message
        :es5-strict
        :externs-validation
        :fileoverview-jsdoc
        :global-this
        :internet-explorer-checks
        :invalid-casts
        :missing-properties
        :non-standard-jsdoc
        :strict-module-dep-check
        :tweaks
        :undefined-names
        :undefined-variables
        :unknown-defines
        :visiblity]
       (map #(vector % (ref-schema 'ClosureWarningValue)))
       (into {})))

(def cljs-compiler-rules
  ;; compiler options
  (distinct
   (concat
    (spec 'CompilerOptions
         {:main                      (ref-schema 'SymbolOrString)
          :asset-path                string?
          :output-to                 string?
          :output-dir                string?
          :optimizations             (ref-schema 'CompilerOptimization)
          :source-map                (ref-schema 'BoolOrString)
          :verbose                   (ref-schema 'Boolean)
          :pretty-print              (ref-schema 'Boolean)
          :target                    :nodejs
          :foreign-libs              [{:file string?
                                       :provides [string?]
                                       :file-min string?
                                       :requires [string?]
                                       :module-type (ref-schema 'ModuleType)
                                       :preprocess (ref-schema 'Named)}]
          :externs                   [string?]
                                     ;; TODO keys only work with direct predicates
          :modules                   {named?
                                      (ref-schema 'ModuleConfigEntry)} 
          :source-map-path           string?
          :source-map-timestamp      (ref-schema 'Boolean)
          :cache-analysis            (ref-schema 'Boolean)
          :recompile-dependents      (ref-schema 'Boolean)
          :static-fns                (ref-schema 'Boolean)
          :warnings                  (ref-schema 'CompilerWarnings)
          :elide-asserts             (ref-schema 'Boolean)
          :pseudo-names              (ref-schema 'Boolean)
          :print-input-delimiter     (ref-schema 'Boolean)
          :output-wrapper            (ref-schema 'Boolean)
          :libs                      [string?]
          :preamble                  [string?]
          :hashbang                  (ref-schema 'Boolean)
          :compiler-stats            (ref-schema 'Boolean)
          :language-in               (ref-schema 'JavaScriptLanguage)
          :language-out              (ref-schema 'JavaScriptLanguage)
          :closure-warnings          (ref-schema 'ClosureWarnings)
          :closure-defines           {string-or-symbol?
                                      (ref-schema 'ClosureDefineValue)}
          :closure-extra-annotations [string?]
          :anon-fn-naming-policy     (ref-schema 'AnonFnNamingPolicy)
          :optimize-constants        (ref-schema 'Boolean)
          :parallel-build            (ref-schema 'Boolean)
          :devcards                  (ref-schema 'Boolean)})
   (or-spec 'CompilerWarnings
            (ref-schema 'Boolean)
            CompilerWarningsSpec)
   (or-spec 'ClosureWarnings
            (ref-schema 'Boolean)
            ClosureWarningsSpec)
   (spec 'ModuleConfigEntry
         {:output-to string?
          :entries [string?]
          :depends-on [named?]})
   (or-spec 'ClosureDefineValue number? string? (ref-schema 'Boolean))
   (or-spec 'ClosureWarningValue :error :warning :off)
   (or-spec 'AnonFnNamingPolicy :off :unmapped :mapped)
   (or-spec 'JavaScriptLanguage :ecmascript3 :ecmascript5 :ecmascript5-strict)
   (or-spec 'ModuleType :commonjs :amd :es6)
   (or-spec 'CompilerOptimization :none :whitespace :simple :advanced)
   (doc 'CompilerOptions
        "Options to be passed to the ClojureScript Compiler"
        {:output-to "The path to the JavaScript file that will be output.\n\n  :output-to \"resources/public/js/main.js\""
         :output-dir
         "Sets the output directory for temporary files used during compilation. Defaults to \"out\".\n\n :output-dir \"resources/public/js/out\""

         }
        ))))

(def shared-type-rules
  (distinct
   (concat
    (or-spec 'Boolean true false)
    ;; need a unique function
    (spec 'Integer (fn [x] (integer? x)))
    (or-spec 'BoolOrString string? (ref-schema 'Boolean))
    (or-spec 'SymbolOrString string? symbol?)
    (or-spec 'Named string? symbol? keyword?))))

#_(spec 'RootMap
          {:figwheel  (ref-schema 'FigwheelOptions)
           :cljsbuild (ref-schema 'CljsbuildOptions)})

(def figwheel-docs
  (distinct
   (concat
    (doc 'RootMap "Top level configuration map. Most often top level keys in project.clj"
         {:figwheel "A map of options for the Figwheel system and server."
          :cljsbuild "A map of lein-cljsbuild options. Figwheel also uses the ClojureScript build configurations found in the cljsbuild options."})
    (doc 'FigwheelOptions "Figwheel Server and System Options"
         {:http-server-root (str "A string that specifies a sub directory on your resource path. "
                                 "This is the path to the static resources that will be served by the figwheel server. "
                                 "This defaults to 'public' and should never be blank.")
          :server-port (str "An integer that the figwheel server should bind.  This defaults to 3449")
          :server-ip   (str "The network interface that the figwheel server will listen on.  This defaults to 'localhost'.")
          :css-dirs (str "A vector of paths from the project root to the location of your css files. "
                         "These files will be watched for changes and the figwheel client will attempt to reload them.")
          :reload-clj-files (str "Either false or a Map like {:cljc false :clj true}. "
                                 "False will disable the reloading of clj files when they change. "
                                 "You can also declare that you want to exclude .cljc or .clj files "
                                 "from the auto reloading. Default: true")
          :open-file-command (str "A path to an executable shell script that will be passed a file and line information "
                                  "for a particualr compilation error or warning.")
          :ring-handler (str "If you want to embed a ring handler into the figwheel http-kit server; "
                             "this is for simple ring servers, if this doesn't work for you just run your own server. Default: Off")
          :repl (str "A Boolean value indicated wether to run a ClojureScript "
                     "REPL after the figwheel process has launched. Default: true")
          :nrepl-port (str "An integer indicating that you would like figwheel to "
                           "lauch nREPL from within the figwheel process and what "
                           "port you would like it to launch on. Default: off")
          :nrepl-middleware (str "A vector of strings indicating the nREPL middleware you want included when nREPL launches.")
          }
         )
    (doc 'ReloadCljFiles "A map indicating which type of clj files should be reloaded on change."
         {:clj (str "A boolean indicating whether you want changes to clj files to trigger a "
                    "reloading of the clj file and the dependent cljs files.")
          :cljc (str "A boolean indicating whether you want changes to cljc files to trigger a "
                     "reloading of the clj file and the dependent cljs files.")})
    (doc 'CljsbuildOptions "A map of options used by lein-cljsbuild and lein-figwheel"
         {:builds (str "The :builds option should be set to either a sequence of build config maps or a map of build configs. "
                       "Each map will be treated as a separate, independent, ClojureScript compiler configuration.")
          :repl-listen-port (str "When using a ClojureScript REPL, this option controls what port "
                                 "it listens on for a browser to connect to.  Defaults to 9000.")
          :repl-launch-commands (str "The keys in this map identify repl-launch commands.  The values are "
                                     "sequences representing shell commands like [command, arg1, arg2, ...]. "
                                     "Defaults to the empty map")
          :test-commands (str "The keys in this map identify test commands.  The values are sequences "
                              "representing shell commands like [command, arg1, arg2, ...].  Note that "
                              "the :stdout and :stderr options work here as well. Defaults to the empty map.")
          :crossovers (str "Super deprecated. You should not be using :crossovers. Please use .cljc functionality.")
          :crossover-path (str "Super deprecated. You should not be using :crossovers. Please use .cljc functionality.")
          :crossover-jar (str "Super deprecated. You should not be using :crossovers. Please use .cljc functionality.") })
    (doc 'BuildOptionsMap "A map of options that specifies a ClojrueScript 'build'"
         {:id "A Keyword, String or Symbol that identifies this build."
          :source-paths  (str "A vector of paths to your cljs source files. These paths should be relative from the"
                              "root of the project to the root the namespace. "
                              "For example, if you have an src/example/core.cljs file that contains a "
                              "example.core namespace, the source path to this file is \"src\"")
          :figwheel (str "Either the Boolean value true or a Map of options to be passed to the figwheel client. "
                         "Supplying a true value or a map indicates that you want the figwheel client "
                         "code to be injected into the build. ")
          :compiler "A map of options are passed directly to the ClojureScript compiler."
          :notify-command (str "If a :notify-command is specified, it will be called when compilation succeeds"
                               "or fails, and a textual description of what happened will be appended as the "
                               "last argument to the command.  If a more complex command needs to be constructed, "
                               "the recommendation is to write a small shell script wrapper. "
                               "Defaults to nil (disabled).")})
    (doc 'FigwheelClientOptions "A map of options that will be passed to the figwheel client."
         {:websocket-host (str "A String specifying the host part of the Figwheel websocket URL. This defaults to "
                               "\"localhost\".  If you have JavaScript clients that need to access Figwheel "
                               "that are not local you can supply the IP address of your machine "
                               "here. You can also specify :js-client-host and the "
                               "Figwheel client will use the js/location.host of the client.")
          
          })
    ))
  )


(def figwheel-cljsbuild-rules
  (distinct
   (concat
    shared-type-rules
    cljs-compiler-rules
    (spec 'FigwheelOptions
          {:http-server-root  string?
                                        ; :builds            (ref-schema 'FigwheelOnlyBuilds)
           :server-port       integer?
           :server-ip         string?
           :css-dirs          [string?]
           :ring-handler      (ref-schema 'Named)
           :reload-clj-files  (ref-schema 'ReloadCljFiles)
           :open-file-command string?
           :repl              (ref-schema 'Boolean)
           :nrepl-port        integer?
           :nrepl-middleware  [(ref-schema 'Named)]})
    (or-spec 'ReloadCljFiles
             (ref-schema 'Boolean)
             {:clj  (ref-schema 'Boolean)
              :cljc (ref-schema 'Boolean)})
    (spec 'CljsbuildOptions
          {
           :builds               (ref-schema 'CljsBuilds)
           :repl-listen-port     integer?
           :repl-launch-commands {named? [(ref-schema 'Named)]}
           :test-commands        {named? [(ref-schema 'Named)]}
           :crossovers           [anything?]
           :crossover-path       [anything?]
           :crossover-jar        (ref-schema 'Boolean)})
    (or-spec 'CljsBuilds
             [(ref-schema 'BuildOptionsMap)]
             {named? (ref-schema 'BuildOptionsMap)})
    (spec 'BuildOptionsMap
          { :id              (ref-schema 'Named)
           :source-paths    [string?]
           :figwheel        (ref-schema 'FigwheelClientOptions)
           :compiler        (ref-schema 'CompilerOptions)
           :notify-command  [string?]
           :jar             (ref-schema 'Boolean)
           :incremental     (ref-schema 'Boolean)
           :assert          (ref-schema 'Boolean) })
    (or-spec 'FigwheelClientOptions
             (ref-schema 'Boolean)
             {:websocket-host      (ref-schema 'WebsocketHost)
              :websocket-url       string?
              :on-jsload           (ref-schema 'Named)
              :before-jsload       (ref-schema 'Named)
              :on-cssload          (ref-schema 'Named)
              :on-message          (ref-schema 'Named)
              :on-compile-fail     (ref-schema 'Named)
              :on-compile-warning  (ref-schema 'Named)
              :reload-dependents   (ref-schema 'Boolean)
              :debug               (ref-schema 'Boolean)
              :autoload            (ref-schema 'Boolean)
              :heads-up-display    (ref-schema 'Boolean)
              :load-warninged-code (ref-schema 'Boolean)
              :retry-count         integer?
              :devcards            (ref-schema 'Boolean)
              :eval-fn             (ref-schema 'Named)})
    (or-spec 'WebsocketHost :js-client-host string?)
    figwheel-docs)))

(def schema-rules-base
  (concat
   (spec 'RootMap
         {:figwheel  (ref-schema 'FigwheelOptions)
          :cljsbuild (ref-schema 'CljsbuildOptions)})
   figwheel-cljsbuild-rules))

(def schema-rules (index-spec schema-rules-base))

;; alright everyting below is the result of being so wishywashy about
;; configuration and now I'm having to support multiple ways of doing things

(defn get-keylike [ky mp]
  (if-let [val (get mp ky)]
    [ky val]
    (when-let [res (not-empty
                    (sort-by
                     #(-> % first -)
                     (filter
                      #(first %)
                      (map (fn [[k v]] [(and (tc/similar-key 0 k ky)
                                            (map? v)
                                            (tc/ky-distance k ky))
                                       [k v]]) mp))))]
      (-> res first second))))

#_(defn get-keyslike [kys mp]
  (into {} (map #(get-keylike % mp) kys)))

(defn validate-only-figwheel-rules [config]
  (index-spec
   (spec 'RootMap         {:figwheel (ref-schema 'FigwheelOptions)})
   (spec 'FigwheelOptions {:builds   (ref-schema 'CljsBuilds)})
   (requires-keys 'FigwheelOptions :builds)
   (requires-keys 'RootMap :figwheel)
   (let [builds (get-in config [:figwheel :builds])]
     (when (tc/sequence-like? builds)
       (requires-keys 'BuildOptionsMap :id)))
   figwheel-cljsbuild-rules))

#_(validate-only-figwheel-rules {:figwheel {:builds []}})

(defn validate-regular-rules [config]
  (index-spec
   schema-rules-base
   (requires-keys 'CljsbuildOptions :builds)
   (requires-keys 'RootMap :cljsbuild)
   (let [builds (get-in config [:cljsbuild :builds])]
     (when (tc/sequence-like? builds)
       (requires-keys 'BuildOptionsMap :id)))))

(defn validate-figwheel-edn-rules [config]
  (index-spec
   figwheel-cljsbuild-rules
   (spec 'FigwheelOptions {:builds   (ref-schema 'CljsBuilds)})
   (requires-keys 'FigwheelOptions :builds)
   (let [builds (get config :builds)]
     (when (tc/sequence-like? builds)
       (requires-keys 'BuildOptionsMap :id)))))

(defn validate-project-config [config]
  (let [[cljb-k cljb-v] (get-keylike :cljsbuild config)
        [fig-k  fig-v]  (get-keylike :figwheel config)]
    (if (and fig-k (get fig-v :builds))
      (let [conf {fig-k fig-v}]
        (print-one-error (validate-only-figwheel-rules conf) 'RootMap conf))
      (let [conf (into {} (filter (comp not nil? first) [[fig-k  fig-v]
                                                         [cljb-k cljb-v]]))]
        (print-one-error (validate-regular-rules conf) 'RootMap conf)))))

(defn validate-figwheel-edn-file [config]
  (print-one-error (validate-figwheel-edn-rules config)
                'FigwheelOptions
                config))

(defn validate-config-data [config figwheel-options-only?]
  (if figwheel-options-only?
    (validate-figwheel-edn-file config)
    (validate-project-config config)))

(defn file-change-wait [file timeout]
  (let [orig-mod (.lastModified (io/file file))
        time-start (System/currentTimeMillis)]
    (loop []
      (let [last-mod (.lastModified (io/file (str file)))
            curent-time (System/currentTimeMillis)]
        (Thread/sleep 100)
        (when (and (= last-mod orig-mod)
                   (< (- curent-time time-start) timeout))
          (recur))))))

(defn get-choice [choices]
  (let [ch (string/trim (read-line))]
    (if (empty? ch)
      (first choices)
      (if-not ((set (map string/lower-case choices)) (string/lower-case (str ch)))
        (do
          (println (str "Amazingly, you chose '" ch  "', which uh ... wasn't one of the choices.\n"
                        "Please choose one of the following: "(string/join ", " choices)))
          (get-choice choices))
        ch))))

(defn validate-loop [get-data-fn options]
  (let [{:keys [figwheel-options-only file]} options]
    (if-not (.exists (io/file file))
      (do
        (println "Configuration file" (str (:file options)) "was not found")
        (System/exit 1))
      (let [file (io/file file)]
        (println "Figwheel: Validating the configuration found in" (str file))
        (loop [fix false]
          (let [config (get-data-fn)]
            (if (not (validate-config-data config figwheel-options-only))
              config
              (do
                (println "Figwheel: There are errors in your configuration file" (str file))
                (let [choice (or (and fix "f")
                                 (do
                                   (println "Figwheel: Would you like to:")
                                   (println "(f)ix the error live while Figwheel watches for config changes?")
                                   (println "(q)uit and fix your configuration?")
                                   (println "(s)tart figwheel anyway?")
                                   (print "Please choose f, q or s and then hit enter[f]: ")
                                   (flush)
                                   (get-choice ["f" "q" "s"])))]
                  (condp = choice
                    "q" false
                    "s" config
                    "f" (if (:file options)
                          (do
                            (println "Figwheel: Waiting for you to edit and save your" (str file) "file ...")
                            (file-change-wait file (* 120 1000))
                            (recur true))
                          (do ;; this branch shouldn't be taken
                            (Thread/sleep 1000)
                            (recur true)))))))))))))

(comment
  ;; figure out
  (defn get-all-keys [rules]
    (for [[k & ks] (rules :-)] k))

  (distinct
   (for [k (get-all-keys schema-rules)
        k2 (get-all-keys schema-rules)
         :when (and
                (not= k k2)
                (tc/similar-key 0 k k2))]
    #{k k2}))

  )
