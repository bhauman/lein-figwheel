(ns figwheel-sidecar.config-check.validate-config
  (:require
   [figwheel-sidecar.utils.fuzzy :as fuz]
   [figwheel-sidecar.config-check.document :refer [get-docs]]
   [figwheel-sidecar.config-check.ansi :refer [color color-text with-color]]
   [figwheel-sidecar.config-check.type-check
    :as tc
    :refer [spec or-spec ref-schema named? anything?
            doc
            requires-keys
            assert-not-empty
            string-or-symbol?
            type-checker type-check print-one-error index-spec with-schema] :as tc]
   [fipp.engine :refer [pprint-document]]
   [clojure.string :as string]
   [clojure.java.io :as io]))

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
          :preloads                  [symbol?]
          :asset-path                string?
          :output-to                 string?
          :output-dir                string?
          :optimizations             (ref-schema 'CompilerOptimization)
          :source-map                (ref-schema 'BoolOrString)
          :verbose                   (ref-schema 'Boolean)
          :pretty-print              (ref-schema 'Boolean)
          :target                    :nodejs
          :foreign-libs              [(ref-schema 'ForeignLib)]
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
          :devcards                  (ref-schema 'Boolean)

          :dump-core                 (ref-schema 'Boolean)
          :emit-constants            (ref-schema 'Boolean)
          
          :warning-handlers          [anything?]
          :source-map-inline         (ref-schema 'Boolean)
          :ups-libs                  [string?]
          :ups-externs               [string?]
          :ups-foreign-libs          [(ref-schema 'ForeignLib)]
          :closure-output-charset    string?

          :external-config           {keyword? map?}})
    (spec 'ForeignLib {:file string?
                       :provides [string?]
                       :file-min string?
                       :requires [string?]
                       :module-type (ref-schema 'ModuleType)
                       :preprocess (ref-schema 'Named)} )
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
   (or-spec 'JavaScriptLanguage
            :ecmascript3
            :ecmascript5
            :ecmascript5-strict
            :ecmascript6
            :ecmascript6-typed
            :ecmascript6-strict
            :no-transpile)
   (or-spec 'ModuleType :commonjs :amd :es6)
   (or-spec 'CompilerOptimization :none :whitespace :simple :advanced)
   )))

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

(def figwheel-options-rules
  (doall
   (distinct
    (concat
     (or-spec 'HawkWatcher :barbary :java :polling)
     (spec 'HawkOptionsMap {:watcher (ref-schema 'HawkWatcher)})
     (or-spec 'ReloadCljFiles
              (ref-schema 'Boolean)
              {:clj  (ref-schema 'Boolean)
               :cljc (ref-schema 'Boolean)})
     (spec 'FigwheelOptions
          {:http-server-root  string?
           ; :builds is added below
           :server-port       integer?
           :server-ip         string?
           :css-dirs          [string?]
           :ring-handler      (ref-schema 'Named)
           :builds-to-start   [(ref-schema 'Named)]
           :reload-clj-files  (ref-schema 'ReloadCljFiles)
           :server-logfile    string?
           :open-file-command string?
           :repl              (ref-schema 'Boolean)
           :nrepl-port        integer?
           :nrepl-host        string?
           :hawk-options      (ref-schema 'HawkOptionsMap)
           :nrepl-middleware  [(ref-schema 'Named)]
           :validate-config   (ref-schema 'Boolean)
           :load-all-builds   (ref-schema 'Boolean)
           :ansi-color-output (ref-schema 'Boolean)})
         (get-docs ['FigwheelOptions])))))

(def build-options-rules
  (doall
   (distinct
    (concat
     (or-spec 'WebsocketHost :js-client-host :server-ip :server-hostname string?)
     (or-spec 'FigwheelClientOptions
              (ref-schema 'Boolean)
              {:build-id            string?
               :websocket-host      (ref-schema 'WebsocketHost)
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
               :eval-fn             (ref-schema 'Named)
               :open-urls           [string?]})
     (spec 'BuildOptionsMap
           {:id              (ref-schema 'Named)
            :source-paths    [string?]
            :figwheel        (ref-schema 'FigwheelClientOptions)
            :compiler        (ref-schema 'CompilerOptions)
            :notify-command  [string?]
            :jar             (ref-schema 'Boolean)
            :incremental     (ref-schema 'Boolean)
            :assert          (ref-schema 'Boolean)
            :warning-handlers [anything?]})
     (assert-not-empty 'BuildOptionsMap :source-paths)
     (requires-keys 'BuildOptionsMap :source-paths :compiler)
     (get-docs ['CompilerOptions
                'FigwheelClientOptions
                'BuildOptionsMap
                'ReloadCljFiles])))))

(def common-validation-rules-base
  (doall
   (distinct
    (concat
     shared-type-rules
     cljs-compiler-rules
     figwheel-options-rules
     build-options-rules))))

(def figwheel-cljsbuild-rules
  (doall
   (distinct
    (concat
     common-validation-rules-base
     (spec 'CljsbuildOptions
          {:builds               (ref-schema 'CljsBuilds)
           :repl-listen-port     integer?
           :repl-launch-commands {named? [(ref-schema 'Named)]}
           :test-commands        {named? [(ref-schema 'Named)]}
           :crossovers           [anything?]
           :crossover-path       [anything?]
           :crossover-jar        (ref-schema 'Boolean)})
    (or-spec 'CljsBuilds
             [(ref-schema 'BuildOptionsMap)]
             {named? (ref-schema 'BuildOptionsMap)})
    (get-docs
     ['CljsbuildOptions])))))

(def schema-rules-base
  (concat
   (spec 'RootMap
         {:figwheel  (ref-schema 'FigwheelOptions)
          :cljsbuild (ref-schema 'CljsbuildOptions)})
   figwheel-cljsbuild-rules
   (get-docs ['RootMap])))

;; alright everyting below is the result of being so wishywashy about
;; configuration and now I'm having to support multiple ways of doing things

(def api-config-rules-base
  (doall
   (distinct
    (concat
     common-validation-rules-base
     (spec 'Builds [(ref-schema 'BuildOptionsMap)])
     (spec 'ApiRootMap
           {:all-builds (ref-schema 'Builds)
            :figwheel-options (ref-schema 'FigwheelOptions)
            :build-ids [string?]})
     (requires-keys 'ApiRootMap :figwheel-options :all-builds)
     (assert-not-empty 'ApiRootMap :all-builds)
     #_(get-docs ['ApiRootMap])))))

(defn raise-one-error [rules root {:keys [data file type] :as config-data}]
  (tc/with-schema rules
    (when-let [single-error (tc/type-check-one-error root data)]
      (let [message (with-out-str (tc/print-error (assoc single-error :orig-config data)))]
        (throw
         (ex-info message
                  {:reason :figwheel-configuration-validation-error
                   :validation-error single-error
                   :config-data config-data})))
      single-error)))

(defn validate-figwheel-config-data [config-data]
  (raise-one-error (index-spec api-config-rules-base)
                   'ApiRootMap
                   config-data))

(defn validate-only-figwheel-rules [config]
  (index-spec
   (spec 'RootMap         {:figwheel (ref-schema 'FigwheelOptions)})
   (spec 'FigwheelOptions {:builds   (ref-schema 'CljsBuilds)})
   (requires-keys 'FigwheelOptions :builds)
   (assert-not-empty 'FigwheelOptions :builds)
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
   (assert-not-empty 'CljsbuildOptions :builds)
   (requires-keys 'RootMap :cljsbuild)
   (let [builds (get-in config [:cljsbuild :builds])]
     (when (tc/sequence-like? builds)
       (requires-keys 'BuildOptionsMap :id)))))

(defn validate-figwheel-edn-rules [config]
  (index-spec
   figwheel-cljsbuild-rules
   (spec 'FigwheelOptions {:builds   (ref-schema 'CljsBuilds)})
   (requires-keys 'FigwheelOptions :builds)
   (assert-not-empty 'FigwheelOptions :builds)   
   (let [builds (get config :builds)]
     (when (tc/sequence-like? builds)
       (requires-keys 'BuildOptionsMap :id)))))

(defn validate-project-config-data [{:keys [data] :as config-data}]
  (let [[cljb-k cljb-v] (fuz/get-keylike :cljsbuild data)
        [fig-k  fig-v]  (fuz/get-keylike :figwheel data)]
    (if (and fig-k (get fig-v :builds))
      (let [conf {fig-k fig-v}]
        (raise-one-error (validate-only-figwheel-rules conf) 'RootMap (assoc config-data :data conf)))
      (let [conf (into {} (filter (comp not nil? first) [[fig-k  fig-v]
                                                         [cljb-k cljb-v]]))]
        (raise-one-error (validate-regular-rules conf) 'RootMap (assoc config-data :data conf))))))

#_(with-color
     (validate-project-config-data
      {:file (io/file "project.clj")
       :type :lein-project
       :data
       {:cljsbuild
        {:builds [{:id "dev" :source-paths ["src" ]
                   :figwheel true
                   :compiler {}}]}
        :figwheel {}}}))

#_(validate-figwheel-config-data {:file (io/file "project.clj")
                                  :data
                                  {:figwheel-options {}
                                   :all-builds [{:id "dev" :source-paths ["src" ]
                                                 :figwheel true
                                                 :build-options {}}]}})

#_(validate-figwheel-config-data {:file (io/file "project.clj")
                                  :data
                                  {:figwheel-options {}
                                   :all-builds [{:id "dev" :source-paths ["src" ]
                                                 :figwheel true
                                                 :build-options
                                                 {:output-dir "out"
                                                  :main 'eamplse.core
                                                  :asset-path "js/out"}}]}})


(defn validate-figwheel-edn-config-data [{:keys [data] :as config-data}]
  (raise-one-error (validate-figwheel-edn-rules data)
                   'FigwheelOptions
                   config-data))

#_(defn validate-config-data [config figwheel-options-only?]
  (if figwheel-options-only?
    (validate-figwheel-edn-file config)
    (validate-project-config config)))


(comment
  ;; figure out
  (def schema-rulest (index-spec schema-rules-base))

  (defn get-all-keys [rules]
    (for [[k & ks] (rules :-)] k))

  (distinct
   (for [k (get-all-keys schema-rulest)
        k2 (get-all-keys schema-rulest)
         :when (and
                (not= k k2)
                (fuz/similar-key 0 k k2))]
    #{k k2}))

  )
