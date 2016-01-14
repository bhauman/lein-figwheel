(ns figwheel-sidecar.config-validation
  (:require
   [clj-fuzzy.metrics :as metrics]
   [fipp.engine :refer [pprint-document]]
   [figwheel-sidecar.ansi :refer [color]]
   [schema.core :as s]
   [schema.spec.leaf :as leaf]
   [schema.spec.core :as spec]
   [clojure.walk :as walk]
   [clojure.zip :as zip]
   [clojure.java.io :as io]))

(def comp-ex
  { :main 'example.core
    :asset-path "js/out"
    :output-to "resources/public/js/example.js"
    :output-dir "resources/public/js/out"
    :source-map-timestamp true
    :libs ["libs_src" "libs_sscr/tweaky.js"]
   ;; :externs ["foreign/wowza-externs.js"]
    :foreign-libs [{:file "foreign/wowza.js"
                    :provides ["wowzacore"]}]
   ;; :recompile-dependents true
    :optimizations :noner})

(defrecord NamedSchema []
  s/Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this
                                                  #(or (string? %)
                                                       (symbol? %)
                                                       (keyword? %))
                                                  #(list 'or
                                                         (list 'string? %)
                                                         (list 'symbol? %)
                                                         (list 'keyword? %)))))
  (explain [this] '(or string? symbol? keyword?)))

(s/check (->NamedSchema) 5)


(def CompilerWarnings
  (->>
   (list
    :undeclared-ns-form
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
    :invoke-ctor)
   (map #(vector (s/optional-key %) s/Bool))
  (into {})))

(def ClosureWarnings
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
       (map #(vector (s/optional-key %) (s/enum :error :warning :off)))
       (into {})))

(def CompilerOptions
  {(s/optional-key :main)           (s/cond-pre s/Symbol s/Str)
   (s/optional-key :asset-path)     s/Str
   (s/optional-key :output-to)      s/Str
   (s/optional-key :output-dir)     s/Str
   (s/optional-key :optimizations)  (s/enum :none :whitespace :simple :advanced)
   (s/optional-key :source-map)     (s/cond-pre s/Bool s/Str)
   (s/optional-key :verbose)        s/Bool
   (s/optional-key :pretty-print)   s/Bool
   (s/optional-key :target)         (s/eq :nodejs)
   (s/optional-key :foreign-libs)   [{:file s/Str ;; could specify  that this is a url path
                                      :provides [s/Str]
                                      (s/optional-key :file-min) s/Str
                                      (s/optional-key :requires) [s/Str]
                                      (s/optional-key :module-type) (s/enum :commonjs :amd :es6)}]
   (s/optional-key :externs)        [s/Str]
   (s/optional-key :modules)                s/Any ;; TODO
   (s/optional-key :source-map-path)        s/Str
   (s/optional-key :source-map-timestamp)   s/Bool
   (s/optional-key :cache-analysis)         s/Bool
   (s/optional-key :recompile-dependents)   s/Bool
   (s/optional-key :static-fns)             s/Bool
   (s/optional-key :warnings)               (s/cond-pre s/Bool CompilerWarnings)
   (s/optional-key :elide-asserts)          s/Bool
   (s/optional-key :pseudo-names)           s/Bool
   (s/optional-key :print-input-delimiter)  s/Bool
   (s/optional-key :output-wrapper)         s/Bool   
   (s/optional-key :libs)                   [s/Str] ;; a path?
   (s/optional-key :preamble)               [s/Str] ;; a path?
   (s/optional-key :hashbang)               s/Bool
   (s/optional-key :compiler-stats)         s/Bool
   (s/optional-key :language-in)            (s/enum :ecmascript3 :ecmascript5 :ecmascript5-strict)
   (s/optional-key :language-out)           (s/enum :ecmascript3 :ecmascript5 :ecmascript5-strict)
   (s/optional-key :closure-warnings)       ClosureWarnings
   (s/optional-key :closure-defines )       {s/Any s/Any}
   (s/optional-key :closure-extra-annotations) #{s/Str}
   (s/optional-key :anon-fn-naming-policy) (s/enum :off :unmapped :mapped)
   (s/optional-key :optimize-constants)    s/Bool
   (s/optional-key :parallel-build)        s/Bool
   (s/optional-key :devcards)              s/Bool})

(def FigwheelClientOptions
  {(s/optional-key :websocket-host)      (s/cond-pre s/Str (s/eq :js-client-host))
   (s/optional-key :websocket-url)       s/Str
   (s/optional-key :on-jsload)           (s/cond-pre s/Symbol s/Str)
   (s/optional-key :before-jsload)       (s/cond-pre s/Symbol s/Str)
   (s/optional-key :on-cssload)          (s/cond-pre s/Symbol s/Str)
   (s/optional-key :on-compile-fail)     (s/cond-pre s/Symbol s/Str)
   (s/optional-key :on-compile-warning)  (s/cond-pre s/Symbol s/Str)
   (s/optional-key :reload-dependents)   s/Bool
   (s/optional-key :debug)               s/Bool   
   (s/optional-key :autoload)            s/Bool
   (s/optional-key :heads-up-display)    s/Bool
   (s/optional-key :load-warninged-code) s/Bool
   (s/optional-key :retry-count)         s/Int
   (s/optional-key :devcards)            s/Bool 
   (s/optional-key :eval-fn)             (s/cond-pre (s/eq false) (s/cond-pre s/Symbol s/Str))})

(def FigwheelBuildOptionsMap
  {:source-paths [s/Str]
   :compiler CompilerOptions
   (s/optional-key :figwheel) (s/cond-pre s/Bool FigwheelClientOptions)
   (s/optional-key :notify-command) [s/Str]})

(def BuildOptionsMap
  (assoc FigwheelBuildOptionsMap
         (s/optional-key :jar)         s/Bool
         (s/optional-key :incremental) s/Bool
         (s/optional-key :assert)      s/Bool))

(def CljsBuilds
  (s/cond-pre
   {(->NamedSchema) BuildOptionsMap}
   [(assoc BuildOptionsMap :id (->NamedSchema))]))

(def CljsbuildOptions
  {:builds CljsBuilds
   (s/optional-key :repl-listen-port) s/Int
   (s/optional-key :repl-launch-commands) {s/Str
                                           [(s/cond-pre s/Str (s/enum :stdout :stderr))]}
   (s/optional-key :test-commands)  {s/Str
                                     [s/Str]}
   (s/optional-key :crossovers)     [s/Any]
   (s/optional-key :crossover-path) [s/Any]
   (s/optional-key :crossover-jar)  s/Bool})

(def FigwheelBuildOptionsMap
  {:source-paths [s/Str]
   :compiler CompilerOptions
   (s/optional-key :figwheel) (s/cond-pre s/Bool FigwheelClientOptions)
   (s/optional-key :notify-command) [s/Str]})

(def FigwheelOnlyBuilds
  (s/cond-pre
   {(->NamedSchema) FigwheelBuildOptionsMap}
   [(assoc FigwheelBuildOptionsMap :id (->NamedSchema))]))

(def FigwheelOptions
  {(s/optional-key :http-server-root)  s/Str
   (s/optional-key :builds)            FigwheelOnlyBuilds
   (s/optional-key :server-port)       s/Int
   (s/optional-key :server-ip)         s/Str
   (s/optional-key :css-dirs)          [s/Str]
   (s/optional-key :ring-handler)      (s/cond-pre s/Symbol s/Str)
   (s/optional-key :reload-clj-files)  (s/cond-pre s/Bool {:clj s/Bool :cljc s/Bool})
   (s/optional-key :open-file-command) s/Str
   (s/optional-key :repl)              s/Bool
   (s/optional-key :nrepl-port)        s/Int
   (s/optional-key :nrepl-middleware)  [s/Str]})

(def FigwheelOverall
  {(s/optional-key :figwheel)   FigwheelOptions
   (s/optional-key :cljsbuild)  CljsbuildOptions})

(defn extract-top-level-keys [schema]
  (keep (fn [[k v]]
          (cond
            (keyword? k) k
            (and (map? k) (:k k)) (:k k)))
        schema))

(defn position-schema [schema]
  {:schema schema
   :possible-map-keys (set (extract-top-level-keys schema))})

(def path-patterns
  {[] (position-schema FigwheelOverall)
   [:figwheel] (position-schema FigwheelOptions)
   [:figwheel :builds] {:schema FigwheelOnlyBuilds}
   [:figwheel :builds ::INT|KEYWORD]   (position-schema (assoc FigwheelBuildOptionsMap :id s/Keyword))
   [:figwheel :builds ::INT|KEYWORD :figwheel] (position-schema FigwheelClientOptions)
   [:figwheel :builds ::INT|KEYWORD :compiler] (position-schema CompilerOptions)
   [:figwheel :builds ::INT|KEYWORD :compiler :warnings] (position-schema CompilerWarnings)
   [:figwheel :builds ::INT|KEYWORD :compiler :closure-warnings] (position-schema ClosureWarnings) 
   [:cljsbuild] (position-schema CljsbuildOptions)
   [:cljsbuild :builds] {:schema CljsBuilds}   
   [:cljsbuild :builds ::INT|KEYWORD] (position-schema (assoc BuildOptionsMap :id Named))
   [:cljsbuild :builds ::INT|KEYWORD :figwheel] (position-schema FigwheelClientOptions)
   [:cljsbuild :builds ::INT|KEYWORD :compiler] (position-schema CompilerOptions)
   [:cljsbuild :builds ::INT|KEYWORD :compiler :warnings] (position-schema CompilerWarnings)
   [:cljsbuild :builds ::INT|KEYWORD :compiler :closure-warnings] (position-schema ClosureWarnings)})

(defn all-map-keys [path-patterns]
  (->> path-patterns
       vals
       (mapcat :possible-map-keys)
       (into #{})))

(def all-keys (all-map-keys path-patterns))

(defn error? [x]
  (or (= x 'missing-required-key)
      (= x 'disallowed-key)
      (instance? schema.utils.ValidationError x)))

;; failure scenarios
;; unknown keys
;; missing keys
(def rr (s/check FigwheelOverall {:cljsbuild {:builds [{:complier 3}]}}))

(def zip-error (partial zip/zipper
                        (fn [x] (and (not (error? x))
                                    #_(not (map-entry? x))
                                    (or (map? x)
                                        (vector? x)
                                        (seq? x))))
                        (fn [x] (cond
                                 (map? x) (seq x)
                                 (vector? x) (seq x)
                                 (seq? x) x))
                        (fn [x children]
                          (cond
                            (map? x) (into {} children)
                            (vector? x) (vec children)
                            (seq? x)    (vec children)))
                        ))

(defn edit-zipper [f zipp-loc]
  (loop [loc zipp-loc]
    (if (zip/end? loc)
      (zip/root loc)
      (recur (zip/next (f loc))))))

(defn map-zipper [func zipp-loc]
  (loop [loc zipp-loc
         accum []]
    (if (zip/end? loc)
      accum
      (recur (zip/next loc)
             (conj accum (func loc))))))

(defn errors-on-keys [schema-result]
  (keep identity
     (map-zipper (fn [loc] (let [node (zip/node loc)]
                       (when (and (map-entry? node) (error? (second node)))
                         {:path (zip/path loc)
                          :error node})))
            (zip-error schema-result))))


(errors-on-keys rr)

(defn to-assoc-path [zip-obj-path]
  (keep (fn [[x y]]
       (cond
         (and (map? x) (map-entry? y)) (first y)
         (map-entry? x) nil
         (vector? x)   (.indexOf x y)
         :else nil))
     (partition 2 1 zip-obj-path)))

#_ (errors-on-keys rr)

#_ (map :error (errors-on-keys rr))

(defn unknown-keys [key-errors]
  (filter #(= 'disallowed-key (second (:error %))) key-errors))

(def ex-error (first (unknown-keys (errors-on-keys rr))))

(defn closest-map-keys [all-keys key]
  (sort-by (comp - second)
           (map (juxt identity (partial metrics/dice (name key))) all-keys)))

#_(closest-map-keys all-keys :ass)

(defn canonical-path [assoc-path]
  (if (<= 3 (count assoc-path))
    (concat (take 2 assoc-path) [::INT|KEYWORD]
            (drop 3 assoc-path))
    assoc-path))

(defn back-to-assoc-path [assoc-path canonical-path]
  (if (<= 3 (count canonical-path))
    (concat (take 2 canonical-path)
            [(nth assoc-path 2)]
            (drop 3 canonical-path))
    canonical-path))

(defn key-in-path? [key path]
  (some-> path-patterns
          (get path)
          :possible-map-keys
          key))

(defn closest-path-with-key* [k start-path]
  (let [possible-paths (concat (take-while (complement nil?)
                                           (iterate butlast start-path))
                               (keys path-patterns))]
    (first (filter (partial key-in-path? k) possible-paths))))

(defn closest-path-with-key [k assoc-path]
  (back-to-assoc-path
   assoc-path
   (closest-path-with-key* k (canonical-path assoc-path))))

#_(key-in-path? [:figwheel :builds ::INT|KEYWORD] :figwheel)

;; an unknown key is either misplaced
;; or spelled wrong

(defn unknown-key-error [error]
  (let [unknown-key   (-> error :error first)
        assoc-path   (to-assoc-path (:path error))
        closest-key  (ffirst (filter #(< 0.3 (second %)) (closest-map-keys all-keys unknown-key)))
        closest-path (closest-path-with-key closest-key assoc-path)]
    (cond
      ;; misplaced key
      (= closest-key unknown-key)
      {:error-type :miss-placed-key
       :current-path assoc-path
       :original-key unknown-key       
       :map-key unknown-key
       :suggested-path (closest-path-with-key unknown-key assoc-path)}  
      (= closest-path assoc-path)
      {:error-type :miss-spelled-key
       :current-path assoc-path
       :original-key unknown-key
       :map-key closest-key}
      closest-key
      {:error-type :miss-spelled-and-miss-placed-key
       :current-path assoc-path
       :original-key unknown-key       
       :map-key closest-key
       :suggested-path (closest-path-with-key closest-key assoc-path)}
      :else
      {:error-type :unknown-key
       :current-path assoc-path
       :original-key unknown-key })))

(defn missing-required-key [err]
  {:error-type :missing-required-key
   :current-path (to-assoc-path (:path err))
   :map-key (-> err :error first)
   :schema-error (-> err :error second)})

(defn value-error [err]
  {:error-type :wrong-value
   :current-path (to-assoc-path (:path err))
   :map-key (-> err :error first)
   :schema-error (-> err :error second)})

(defn interpret-error [{:keys [error] :as err}]
  (let [error-msg (second error)]
    (cond
      (= 'disallowed-key error-msg) (unknown-key-error err)
      (= 'missing-required-key error-msg)     (missing-required-key err)
      (instance? schema.utils.ValidationError error-msg) (value-error err))))

(defn check-config [config-edn]
  (let [schema-result (s/check FigwheelOverall config-edn)
        key-errors (errors-on-keys schema-result)]
    (map #(assoc % :orig-config config-edn)
         (map interpret-error key-errors))))

;; crazy simplification - gonna regret this
(defn print-path [[x & xs] leaf-node edn]
  (if (and x (get edn x))
    [:group (if (and (not (map? edn)) (integer? x))
              "[{"
              (str (pr-str x ) " {"))
     [:nest 2
      :line
      (print-path xs leaf-node (get edn x))
      ]
     (if (and (not (map? edn)) (integer? x))
       "}]"
       "}")]
    leaf-node))

(defmulti print-error :error-type)

(defmethod print-error :wrong-value [{:keys [orig-config map-key current-path schema-error]}]
  (pprint-document (print-path current-path
                               [:group
                                (color (pr-str map-key) :bold)
                                " "
                                (color (with-out-str
                                         (print schema-error))
                                       :red)
                                :line
                                (color "^ wrong value for config key" :underline :magenta)
                                :line]
                               orig-config)
                   {:width 40}))

(defmethod print-error :missing-required-key [{:keys [orig-config map-key current-path schema-error]}]
  (pprint-document (print-path current-path
                               [:group
                                (color (pr-str map-key) :bold)
                                " "
                                (color (with-out-str
                                         (print schema-error))
                                       :red)
                                :line
                                (color "^ this key is missing from your config" :underline :magenta)
                                :line]
                               orig-config
                               )
                   {:width 40}))

(mapv print-error (check-config {:figwheel {:builds [{:id 5
                                                      :compiler {:output-dir 5}}]}}))

(def r (s/check FigwheelOptions {:http-server-root :hello
                                 :builds [{:id 5 }]
                                 :ring-handler 5
                                 :css-dirs [5]})) 

(s/spec FigwheelOptions)

(s/check FigwheelOptions {:nrepl-middleware [1 2]})

(s/check BuildOptionsMap {:source-paths []})
