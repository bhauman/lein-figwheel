(ns figwheel-sidecar.validate-config
  (:require
   [figwheel-sidecar.ansi :refer [color]]
   [figwheel-sidecar.type-check :refer [spec or-spec ref-schema named? type-checker type-check print-errors index-spec with-schema]]
   [fipp.engine :refer [pprint-document]]
   [clojure.string :as string]))

(defn boolean? [x] (or (true? x) (false? x)))

(defn anything? [x] true)

(defn enum [& args]
  (let [enums (set args)]
    (fn [x] (boolean (enums x)))))

(defn or-pred [& args]
  (fn [x] (boolean (some #(% x) args))))

(def symbol-or-string? (or-pred symbol? string?))

(def bool-or-string? (or-pred boolean? string?))

(def optimisation?    (enum :none :whitespace :simple :advanced))
(def module-type?     (enum :commonjs :amd :es6))

(def javascript-lang? (enum :ecmascript3 :ecmascript5 :ecmascript5-strict))
(def anon-fn-naming-policy? (enum :off :unmapped :mapped))

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
          :repl              boolean?
          :nrepl-port        integer?
          :nrepl-middleware  [named?]})
   (or-spec 'ReloadCljFiles
            boolean?
            {:clj  boolean?
             :cljc boolean?})
   (spec 'CljsbuildOptions
         {:builds (ref-schema 'CljsBuilds)
          :repl-listen-port     integer?
          :repl-launch-commands {named? [named?]}
          :test-commands        {named? [named?]}
          :crossovers           [anything?]
          :crossover-path       [anything?]
          :crossover-jar        boolean?})
   (or-spec 'CljsBuilds
            [(ref-schema 'BuildOptionsMap)]
            {named? (ref-schema 'BuildOptionsMap)})
   (spec 'BuildOptionsMap
         { :id named?
          :source-paths    [string?]
          :figwheel        (ref-schema 'FigwheelClientOptions)
          :compiler        (ref-schema 'CompilerOptions)
          :notify-command  [string?]
          :jar             boolean?
          :incremental     boolean?
          :assert          boolean? })
   (or-spec 'FigwheelClientOptions
            boolean?
            {:websocket-host      (ref-schema 'WebsocketHost)
             :websocket-url       string?
             :on-jsload           named?
             :before-jsload       named?
             :on-cssload          named?
             :on-compile-fail     named?
             :on-compile-warning  named?
             :reload-dependents   boolean?
             :debug               boolean?
             :autoload            boolean?
             :heads-up-display    boolean?
             :load-warninged-code boolean?
             :retry-count         integer?
             :devcards            boolean?
             :eval-fn             (ref-schema 'FigwheelEvalFn)})
   (or-spec 'WebsocketHost :js-client-host string?)
   (or-spec 'FigwheelEvalFn string? named?)
   (spec 'CompilerOptions
         {:main           symbol-or-string?
          :asset-path     string?
          :output-to      string?
          :output-dir     string?
          :optimizations  optimisation?
          :source-map     bool-or-string?
          :verbose        boolean?
          :pretty-print   boolean?
          :target         :nodejs
          :foreign-libs   [{:file string?
                            :provides [string?]
                            :file-min string?
                            :requires [string?]
                            :module-type module-type?}]
          :externs        [string?]
          :modules        anything? ;; TODO
          :source-map-path string?
          :source-map-timestamp boolean?
          :cache-analysis    boolean?
          :recompile-dependents boolean?
          :static-fns  boolean?
          :warnings   (ref-schema 'CompilerWarnings)
          :elide-asserts boolean?
          :pseudo-names boolean?
          :print-input-delimiter boolean?
          :output-wrapper boolean?
          :libs [string?]
          :preamble [string?]
          :hashbang  boolean?
          :compiler-stats  boolean?
          :language-in javascript-lang?
          :language-out javascript-lang?
          :closure-warnings (ref-schema 'ClosureWarnings)
          :closure-defines  {anything? anything?}
          :closure-extra-annotations [string?]
          :anon-fn-naming-policy anon-fn-naming-policy?
          :optimize-constants boolean?
          :parallel-build boolean?
          :devcards boolean?})
   (or-spec 'CompilerWarnings
            (ref-schema 'Boolean)
            CompilerWarningsSpec)
   (or-spec 'ClosureWarnings
            (ref-schema 'Boolean)
            ClosureWarningsSpec)
   (or-spec 'Boolean true false)
   (or-spec 'ClosureWarningValue :error :warning :off)

    
    )
  

  )


(print-errors schema-rules 'CompilerOptions {:closure-warnings {:consta :off}})

#_(with-schema schema-rules
  (time
   (type-checker 'RootMap { :cljsbuild {
                                        :builds [{:id "example"
                                                  :figwheel {
                                                             :websocket-host "localhost"
                                                             :on-jsload      'example.core/fig-reload
                                                             
                                        ; :on-message     'example.core/on-message
                                                             :debug "asdf"
                                                             }
                                                  :compiler { :main 'example.core
                                                             :asset-path "js/out"
                                                             :output-to "resources/public/js/example.js"
                                                             :output-dir "resources/public/js/out"
                                                             :source-map-timestamp true
                                                             :libs ["libs_src" "libs_sscr/tweaky.js"]
                                                             ;; :externs ["foreign/wowza-externs.js"]
                                                             :foreign-libs [{:file "foreign/wowza.js"
                                                                             :provides ["wowzacore"]}]
                                                             ;; :recompile-dependents true
                                                             :optimizations :none}
                                                  }]}
                           
                           :figwheel {
                                      :http-server-root "public" ;; default and assumes "resources" 
                                      :server-port 3449 ;; default
                                      :css-dirs ["resources/public/css"]
                                      :open-file-command "emacsclient"}}
                 {}))
  )

