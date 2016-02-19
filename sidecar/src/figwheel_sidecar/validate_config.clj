(ns figwheel-sidecar.validate-config
  (:require
   [figwheel-sidecar.ansi :refer [color]]
   [figwheel-sidecar.type-check :refer [spec or-spec ref-schema named? anything?
                                        doc
                                        requires-keys
                                        string-or-symbol?
                                        type-checker type-check print-errors index-spec with-schema] :as tc]
   [fipp.engine :refer [pprint-document]]
   [clojure.string :as string]))

(defn enum [& args]
  (let [enums (set args)]
    (fn [x] (boolean (enums x)))))

(defn or-pred [& args]
  (fn [x] (boolean (some #(% x) args))))

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
    (or-spec 'WebsocketHost :js-client-host string?))))

(def schema-rules-base
  (concat
   (spec 'RootMap
         {:figwheel  (ref-schema 'FigwheelOptions)
          :cljsbuild (ref-schema 'CljsbuildOptions)})
   figwheel-cljsbuild-rules))

(def schema-rules (index-spec schema-rules-base))

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
                                            (tc/key-distance k ky))
                                       [k v]]) mp))))]
      (-> res first second))))

(defn get-keyslike [kys mp]
  (into {} (map #(get-keylike % mp) kys)))

(defn validate-only-figwheel-rules [config]
  ; also check if config has vector for builds
  (let [rules-db
        (index-spec
         (spec 'RootMap         {:figwheel (ref-schema 'FigwheelOptions)})
         (spec 'FigwheelOptions {:builds   (ref-schema 'CljsBuilds)})
         (requires-keys 'FigwheelOptions :builds)
         (requires-keys 'RootMap :figwheel)
         (let [builds (get-in config [:figwheel :builds])]
           (when (tc/sequence-like? builds)
             (requires-keys 'BuildOptionsMap :id)))
         figwheel-cljsbuild-rules)]
    (not-empty (print-errors rules-db 'RootMap config))))

#_(validate-only-figwheel-rules {:figwheel {:builds []}})

(defn validate-regular [config]
  (let [rules-db
        (index-spec
         schema-rules-base
         (requires-keys 'CljsbuildOptions :builds)
         (requires-keys 'RootMap :cljsbuild)
         (let [builds (get-in config [:figwheel :builds])]
           (when (tc/sequence-like? builds)
             (requires-keys 'BuildOptionsMap :id))))]
    (not-empty (print-errors rules-db 'RootMap config))))

#_(validate-regular {:figwheel {}
                     :cljsbuild {:builds []}})

(defn validate-project-config [config]
  (let [[cljb-k cljb-v] (get-keylike :cljsbuild config)
        [fig-k  fig-v]  (get-keylike :figwheel config)]
    (if
      (and fig-k
           (get fig-v :builds))
      ;; only validate figwheel
      (validate-only-figwheel {fig-k fig-v})
      ;; validate regular
      (validate-regular       {fig-k  fig-v
                               cljb-k cljb-v}))))

(comment
  (def cljs-option-doc
    (memoize (fn []
               (slurp "https://raw.githubusercontent.com/wiki/clojure/clojurescript/Compiler-Options.md")))))


#_(with-schema schema-rules
  (type-check 'CompilerOptions {:closure-extra-annotations #{2 "asdf"}})

  #_(tc/find-keys-for-type tc/*schema-rules* 'ClosureWarnings)
  #_(tc/find-keys-for-type 'CljsbuildOptions)
  
  )

#_(print-errors schema-rules 'BuildOptionsMap
              {:figwheel {:websocket-host :js-client-host}})



#_(print-errors schema-rules 'CompilerOptions {:main "asdf"
                                               :output-to 5
                                               :anon-fn-naming-policy :off
                                               :closure-warnings {:const :off}
                                               :devcards true
                                               :closure-defines {"asdfas.asdf" "asdf"}
                                               :source-map true
                                               :optimizations :none
                                        ; :language-in :asdf
                                               :foreign-libs [{:file "asdf"
                                                               :provides ["asdf"]
                                                               :module-type :commonj
                                                               }]
                                               
                                               :modules {1 {:output-to "asdf"
                                                            :entries ["asdf"]}}
                                               :closure-extra-annotations #{"asdf" "asd" "asssss" }})
