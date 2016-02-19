(ns figwheel-sidecar.validate-config
  (:require
   [figwheel-sidecar.ansi :refer [color]]
   [figwheel-sidecar.type-check :refer [spec or-spec ref-schema named? anything?
                                        type-checker type-check print-errors index-spec with-schema] :as tc]
   [fipp.engine :refer [pprint-document]]
   [clojure.string :as string]))

(defn enum [& args]
  (let [enums (set args)]
    (fn [x] (boolean (enums x)))))

(defn or-pred [& args]
  (fn [x] (boolean (some #(% x) args))))

(def symbol-or-string? (or-pred symbol? string?))


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

(def schema-rules
  (index-spec
   
   (spec 'RootMap
         {:figwheel  (ref-schema 'FigwheelOptions)
          :cljsbuild (ref-schema 'CljsbuildOptions)})
   (spec 'FigwheelOptions
         {:http-server-root  string?
                                        ; :builds            (ref-schema 'FigwheelOnlyBuilds)
          :server-port       integer?
          :server-ip         string?
          :css-dirs          [string?]
          :ring-handler      named?
          :reload-clj-files  (ref-schema 'ReloadCljFiles)
          :open-file-command string?
          :repl              (ref-schema 'Boolean)
          :nrepl-port        integer?
          :nrepl-middleware  [named?]})
   (or-spec 'ReloadCljFiles
            (ref-schema 'Boolean)
            {:clj  (ref-schema 'Boolean)
             :cljc (ref-schema 'Boolean)})
   (spec 'CljsbuildOptions
         {:builds (ref-schema 'CljsBuilds)
          :repl-listen-port     integer?
          :repl-launch-commands {named? [named?]}
          :test-commands        {named? [named?]}
          :crossovers           [anything?]
          :crossover-path       [anything?]
          :crossover-jar        (ref-schema 'Boolean)})
   (or-spec 'CljsBuilds
            [(ref-schema 'BuildOptionsMap)]
            {named? (ref-schema 'BuildOptionsMap)})
   (spec 'BuildOptionsMap
         { :id named?
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
   (or-spec 'WebsocketHost :js-client-host string?)
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
                                       :module-type (ref-schema 'ModuleType)}]
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
          :closure-defines           {named? anything?}
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
   (or-spec 'Boolean true false)
   ;; need a unique function
   (spec 'Integer (fn [x] (integer? x)))
   (or-spec 'BoolOrString string? (ref-schema 'Boolean))
   (or-spec 'SymbolOrString string? symbol?)
   (or-spec 'ClosureWarningValue :error :warning :off)
   (or-spec 'AnonFnNamingPolicy :off :unmapped :mapped)
   (or-spec 'JavaScriptLanguage :ecmascript3 :ecmascript5 :ecmascript5-strict)
   (or-spec 'ModuleType :commonjs :amd :es6)
   (or-spec 'Named string? symbol? keyword?)
   (or-spec 'CompilerOptimization :none :whitespace :simple :advanced)
   
   )
  

  )

(with-schema schema-rules
  (type-check 'CompilerOptions {:closure-extra-annotations #{2 "asdf"}})

  #_(tc/find-keys-for-type tc/*schema-rules* 'ClosureWarnings)
  #_(tc/find-keys-for-type 'CljsbuildOptions)
  
  )



{:figwheel {:server-port 5}}

#_(print-errors schema-rules 'CompilerOptions {:main "asdf"
                                             :anon-fn-naming-policy :off
                                             :closure-warnings {:const :off}
                                             :devcards true
                                             :source-map true
                                             :optimizations :none
                                        ; :language-in :asdf
                                             :foreign-libs [{:file "asdf"
                                                             :provides ["asdf"]
                                                             :module-type :commonjs
                                                             }]
                                             
                                             :modules {1 {:output-to "asdf"
                                                          :entries ["asdf"]}}
                                             :closure-extra-annotations #{"asdf" "asd" "asssss" }})



#_:Crappersprint-errors

#_(print-errors schema-rules 'RootMap {:cljsbuild
                                     {:builds [{:compiler {:warnings {:const :off}}}]}})



