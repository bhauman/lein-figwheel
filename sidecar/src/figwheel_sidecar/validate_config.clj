(ns figwheel-sidecar.validate-config
  (:require
   [figwheel-sidecar.type-check :refer [spec ref-schema named? type-check!!!]]
   [clojure.string :as string]))

(defn boolean? [x] (or (true? x) (false? x)))

(defn anything? [x] true)

(defn enum [& args]
  (let [enums (set args)]
    (fn [x] (enums x))))

(defn or-pred [& args]
  (fn [x] (some #(% x) args)))

(def symbol-or-string? (or-pred symbol? string?))

(def bool-or-string? (or-pred boolean? string?))

(def optimisation? (enum :none :whitespace :simple :advanced))
(def module-type?  (enum :commonjs :amd :es6))

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
   (map #(vector % boolean?))
   (into {})))

(def schema-rules
  (distinct
   (concat
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
           :reload-clj-files  boolean?
           :open-file-command string?
           :repl              boolean?
           :nrepl-port        integer?
           :nrepl-middleware  [named?]})
    (spec 'FigwheelOptions
          {:reload-clj-files {:clj  boolean?
                              :cljc boolean?}})
    (spec 'CljsbuildOptions
          {:builds (ref-schema 'CljsBuilds)
           :repl-listen-port     integer?
           :repl-launch-commands {named? [named?]}
           :test-commands        {named? [named?]}
           :crossovers           [anything?]
           :crossover-path       [anything?]
           :crossover-jar        boolean?})
    (spec 'CljsBuilds
          {named? (ref-schema 'BuildOptionsMap)})
    (spec 'CljsBuilds
          [(ref-schema 'BuildOptionsMap)])
    (spec 'BuildOptionsMap
          { :id named?
            :source-paths    [string?]
            :figwheel        (ref-schema 'FigwheelClientOptions)
            :compiler        (ref-schema 'CompilerOptions)
            :notify-command  [string?]
            :jar             boolean?
            :incremental     boolean?
            :assert          boolean? })
    (spec 'BuildOptionsMap
          { :figwheel        boolean?})
    (spec 'FigwheelClientOptions
          {:websocket-host      string?
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
           :eval-fn             boolean?})
    (spec 'FigwheelClientOptions
          {:websocket-host      :js-client-host
           :eval-fn             named?})
    (spec 'CompilerOptions
          :main           symbol-or-string?
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
          :warnings    boolean?)
    (spec 'CompilerOptions
          {:warnings (ref-schema 'CompilerWarnings)})
    (spec 'CompilerWarnings CompilerWarningsSpec)
    ))

  )
