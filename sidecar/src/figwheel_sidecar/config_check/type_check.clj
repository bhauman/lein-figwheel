(ns figwheel-sidecar.config-check.type-check
  (:require
   [clojure.pprint :as pprint]

   [fipp.visit :refer [visit]]
   [fipp.edn :as fedn]
   [fipp.engine :refer [pprint-document]]

   [figwheel-sidecar.config-check.ansi :refer [color]]
   [figwheel-sidecar.utils.fuzzy :as fuz]
   [clojure.walk :as walk]
   [clojure.string :as string]
   [clojure.set :refer [difference]]))

(def edn-printer (fedn/map->EdnPrinter {}))

(defn fipp-format-data [data]
  (visit edn-printer data))

(defn log [x] (prn x) x)

(def ^:dynamic *schema-rules* nil)

;; TODO more caching here and maybe compose over a separate caching macro
(defmacro with-schema
  [indexed-rules & body] 
  `(with-redefs [tc-analyze (memoize tc-analyze)
                 type-checker (memoize type-checker)]
     (binding [*schema-rules* ~indexed-rules] ~@body)))

(defn schema-rules [arg]
  (if *schema-rules*
    (*schema-rules* arg)
    (throw (Exception. (str "Type Check Schema is not bound! Please bind type-check/*schema-rules* "
                            "and watch lazyiness, binding scope.")))))

(defn sequence-like? [x]
  (and (not (map? x)) (coll? x)))

(defn seqify [coll]
  (cond
    (map? coll)
    (cons :MAPP
          (map (fn [[a b]] [(seqify a) (seqify b)]) coll))
    (sequence-like? coll)
    (cons :SEQQ (map vector (range) (map seqify coll)))
    :else coll))

(defn map?? [x]
  (and (sequence-like? x) (= (first x) :MAPP)))

(defn list?? [x]
  (and (sequence-like? x) (= (first x) :SEQQ)))

(defn empty?? [x]
  (and (or (map?? x) (list?? x))
       (empty? (rest x))))

(defn prep-key [k]
  (if (fn? k)
    (keyword (str "pred-key_" (hash k)))
    k))

;; TODO examine need for initial recursion step
(defn decompose [type-gen-fn node' s']
  (letfn [(handle-key-type [orig-key predicate-key-name node t]
            (cond
              (fn? orig-key)
              [[predicate-key-name :?- [node :> t]]
               [predicate-key-name := orig-key]]
              (not (nil? orig-key))
              [[orig-key :- [node :> t]]]
              :else []))
          (decomp [node s]
            (if (or (nil? s) (empty? s))
              nil
              (let [[[k' v] & r] s
                    k (prep-key k')
                    t (type-gen-fn node k)]
                (if (or (list?? v) (map?? v))
                  (concat
                   (cons
                    [t :=> (first v)]
                    (handle-key-type k' k node t))
                   (decomp t (rest v))
                   (decomp node r))
                  (concat (cond (fn? v)
                                (cons
                                 [t := v]
                                 (handle-key-type k' k node t))
                                (-> v meta :ref)
                                (handle-key-type k' k node v)
                                :else ;this is a value comparison
                                (cons
                                 [t :== v]
                                 (handle-key-type k' k node t)))
                          (decomp node r))))))]
    (cond
      (or (list?? s') (map?? s'))
      (cons
       [node' :=> (first s')]
       (if (empty?? s') [] (decomp node' (rest s'))))
      (fn? s')
      [[node' := s']]
      (-> s' meta :ref)
      [[node' :-- s']]
      :else
      [[node' :== s']])))

(defn ref-schema [s]
  (vary-meta s #(assoc % :ref true)))

(defn type-gen [node k]
  (symbol (str (name node) k)))

(defn spec [type body]
  (distinct (#'decompose type-gen type (seqify body))))

(defn or-spec [root & body]
  (let [syms (mapv (fn [a] (symbol (str (name root) "||" a)))
                   (range (count body)))]
    (distinct
     (concat
      (mapcat (fn [x b]
             (if (-> b meta :ref)
               (spec root b)
               (spec root (ref-schema x)))) syms body)
      (mapcat
       (fn [x b]
         (when-not (-> b meta :ref)
           (spec x b)))
       syms body)))))

(defn requires-keys [root & key-list]
  (mapv (fn [k] [root :requires-key k]) key-list))

(defn assert-not-empty [root & key-list]
  (mapv (fn [k] [root :not-empty k]) key-list))


;; TODO I'm thinking documentation should only be attached to types
;; this would be much more consistent
(defn doc
  ([root type-doc kd]
   (cons [root :doc-type type-doc]
         (mapv (fn [[k d]] [root :doc-key k d]) kd)))
  ([root type-doc]
   (doc root type-doc [])))

;; Direct Implementation
;; this is still squirrely

(defn index-spec [& spc]
  (let [spc (distinct (apply concat spc))]
    (merge
     (group-by second spc)
     (group-by (fn [x] [:parent (second x) (first (nth x 2))]) (filter #(#{:?- :-} (second %)) spc))
     (group-by (juxt second first) spc))))

(defn fetch-pred [pred-type parent-type]
  (schema-rules [pred-type parent-type]))

(defn leaf-pred? [parent-type]
  (concat (fetch-pred :=  parent-type)
          (fetch-pred :== parent-type)
          (fetch-pred :=> parent-type)))

;; this is really transitive decendents
(defn descendent-typs [t]
  (distinct
   (apply concat
         (for [[_ _ child-type] (schema-rules [:-- t])]
           (cons child-type (descendent-typs child-type))))))

;; this is really transitive ancestor-typs
(defn ancestor-typs [t]
  (distinct
   (apply concat
          (for [[ancestor-type _ child-type] (schema-rules :--)
                :when (= t child-type)]
            (cons ancestor-type (ancestor-typs ancestor-type))))))

(defn all-predicates [parent-type]
  (keep identity (mapcat leaf-pred? (cons parent-type (descendent-typs parent-type)))))

(defmulti apply-pred (fn [f v] (second f)))
(defmethod apply-pred :== [[t _ pred] value] (= value pred))
(defmethod apply-pred :=  [[t _ pred] value] (pred value))
(defmethod apply-pred :=> [[t _ pred] value] (= (cond
                                                  (map? value) :MAPP
                                                  (sequence-like? value) :SEQQ
                                                  :else :_____BAD)
                                                pred))

(defn type-check-pred [pred value state]
  (let [[typ _ pred-op] pred]
    (if-not (apply-pred pred value)
      (let [error {:Error-type :failed-predicate
                   :not pred-op
                   :value value
                   :type-sig (:type-sig state)
                   :path     (:path state)}]
        [(if (not= (-> state :type-sig first) typ)
           (assoc error :sub-type typ)
           error)])
      {:success-type typ})))

;; TODO or relationship
(defn type-check-value [parent-type value state]
  (if-let [preds (all-predicates parent-type)]
    (let [errors   (map #(type-check-pred % value state) preds)
          success? (filter map? errors)]
      (if (not-empty success?)
        {:success-types (map :success-type success?)}
        (apply concat errors)))
    (throw (Exception. (str "parent-type " parent-type "has no predicate.")))))

(defn compound-type? [parent-type]
  (#{:SEQQ :MAPP} (last (first (fetch-pred :=> parent-type)))))

(defn get-types-from-key-parent [parent-type ky]
  (map (comp last last)
       (filter #(= parent-type (-> % last first)) (schema-rules [:- ky]))))

(declare type-checker)

(defn fix-key [k parent-value]
  (if (sequence-like? parent-value) 0 k))

;; not descendent aware
(defn find-keyword-predicate [parent-type]
  (when-let [[pred-id _ [pt _ kt]] (first (schema-rules [:parent :?- parent-type]))]
    (when-let [pred-func (last (first (schema-rules [:= pred-id])))]
      [pred-func kt])))

;; TODO this is what implements or
(defn type-check-key-value [parent-type ky value state]
  (let [next-types (get-types-from-key-parent parent-type (fix-key ky (:parent-value state)))
        state      (update-in state [:path] conj ky)]
    (if (not-empty next-types)
      (let [results (map #(type-checker % value state) next-types)]
        (if (some empty? results) [] (apply concat results)))
      (if-let [[pred-fn next-type] (find-keyword-predicate parent-type)]
        (if (pred-fn ky)
          (type-checker next-type value state)
          [{:Error-type :failed-key-predicate
            :not pred-fn
            :key ky
            :value value
            :type-sig (:type-sig state)
            :path     (:path     state)}])
        [{:Error-type :unknown-key
          :key ky
          :value value
          :type-sig (:type-sig state)
          :path     (:path     state)}]))))

(defn required-keys-for-type [parent-type]
  (set (for [[t _ k]  (schema-rules [:requires-key parent-type])
             :when (= t parent-type)]
         k)))

(defn check-required-keys [parent-type value state]
  (if (map? value)
    (let [res (difference (required-keys-for-type parent-type) (set (keys value)))]
      (mapv (fn [x]
              {:Error-type :missing-required-key
               :path (cons x (:path state))
               :key x
               :value nil
               :parent-value value
               :type-sig (:type-sig state)
               }) res))
    []))

(defn assert-not-empty-keys-for-type [parent-type]
  (set (for [[t _ k]  (schema-rules [:not-empty parent-type])
                  :when (= t parent-type)]
              k)))

(defn check-assert-not-empty [parent-type value state]
  (if (map? value)
    (let [res (filter #(and (contains? value %) (coll? (get value %)) (empty? (get value %)))
                      (assert-not-empty-keys-for-type parent-type))]
      (mapv (fn [x]
              {:Error-type :should-not-be-empty
               :path (cons x (:path state))
               :key x
               :value (get value x)
               :parent-value value
               :type-sig (:type-sig state)
               }) res))
    []))

(defn type-checker-help [parent-type value state]
  (if-not (compound-type? parent-type)
    []
    (let [f (partial mapcat
                     (fn [[k v]]
                       (type-check-key-value parent-type k v (assoc state :parent-value value))))]
      (cond
        (map? value)
        (concat (check-required-keys parent-type value state)
                (check-assert-not-empty parent-type value state)
                (f value))
        (sequence-like? value)
        (f (map vector (range) value))
        :else (throw (Exception. (str "Expected compound type: " (class value)
                                      " is not a Map, Vector, or Sequence")))))))

(defn type-checker [parent-type value state]
  (let [state (update-in state [:type-sig] conj parent-type)]
    (let [res (type-check-value parent-type value state)]
      (if (and (map? res) (:success-types res))
        (let [errors-list (mapv #(type-checker-help % value state) (:success-types res))
              success (first (filter empty? errors-list))]
          (if success
            success
            (apply concat errors-list)))
        res))))

;; public predicates for API

(defn named? [x]
  (or (string? x) (instance? clojure.lang.Named x)))

(defn string-or-symbol? [x]
  (or (string? x) (symbol? x)))

(defn boolean? [x] (or (true? x) (false? x)))

(defn anything? [x] true)

;; 

(defn ancester-key-rules
  "Returns all the key terminal ancester rules for a given type."
  [typ']
  (doall
   (distinct
    (concat
     (for [typ (cons typ' (ancestor-typs typ'))
           [ky _ [parent-type _ t] :as rule] (concat (schema-rules :-) (schema-rules :?-))
           :when (= t typ)]
       rule)))))

(defn concrete-parent [typ']
  (or (not-empty
       (for [[ky _ [parent-type _ t]] (ancester-key-rules typ')]
         t))
      (if (not-empty (concat (schema-rules [:parent :-  typ'])
                             (schema-rules [:parent :?-  typ']) ))
        [typ']
        [])))

(defn parents-for-type [typ']
  (for [[ky _ [parent-type _ t]] (ancester-key-rules typ')]
    [ky parent-type]))

(defn get-paths-for-type [root typ]
  (vec
     (mapcat (fn [[ky pt]]
               (if (= pt root)
                 [[ky]]
                 (mapv #(conj % ky) (get-paths-for-type root pt))))
             (parents-for-type typ))))

(defn get-paths-for-key [root type ky]
  (filter
   #(= (last %) ky)
   (get-paths-for-type root type)))

(defn error-parent-value [{:keys [path orig-config]}]
  (get-in orig-config (reverse (rest path))))

;; analyzing the analysis results
;; This is where the real smarts come in
;; It isn't easy to tell from the code below but this chooses the possible error
;; according to the probabilities that the config matches the defined
;; types

(declare tc-analyze)

(defn detailed-config-analysis [config]
  (doall (tc-analyze config)))

(defn max-val [mp]
  (reduce (fn [[k v] [k1 v1]] (if (< v v1) [k1 v1] [k v])) [nil -1] mp))

(defn which-type-wins? [expected-type types]
  (let [types (if ((set types) expected-type)
                ;; this is a little too subtle
                ;; by adding this to the start of the
                ;; list it ends up being first in the
                ;; ordered-map returned by frequencies
                (cons expected-type types) ;; bias towards expected-type
                types)
        freqs (frequencies types)]
    (first (max-val freqs))))

;; TODO this is redundant which-type-wins? encompases most of this behavior
(defn analysis-type-matches [parent-type analysis]
  (let [types (map first analysis)
        type-set (set types)
        ;; how much info
        total-analysis-paths (count analysis)]
    (cond
      (empty? type-set) {:Error :no-type-match}
      (<= total-analysis-paths 2) (if (type-set parent-type)
                                    true
                                    {:Error :wrong-type :alternate-type (first type-set)})
      (> total-analysis-paths 2)
      (let [preferred-type (which-type-wins? parent-type types)]
        (if (= preferred-type parent-type)
          true
          {:Error :wrong-type :alternate-type preferred-type})))))

(defn limit-analysis-to-type [parent-type analysis]
  (filter #(= (first %) parent-type) analysis))

;; TODO would be nice to have a diferrent message for whole hog replacement
(defn misspelled-key? [parent-type parent-config-value bad-key analysis]
  (let [path-that-start-with-key (->> (limit-analysis-to-type parent-type analysis)
                                      (map rest)
                                      (filter (fn [[ky & xs]]
                                                (and (vector? ky)
                                                     (seq ky)
                                                     (= bad-key (second ky))
                                                     (#{:subst :replace-key} (first ky))))))
        potential-keys (sort-by
                        first
                        (for [[ky & xs] path-that-start-with-key
                              :let [[_ bkey suggested-key] ky
                                    error-count (count (filter vector? xs))]]
                          [error-count suggested-key]))
        ;; filter out keys that arleady exist in current config map
        potential-keys (filter (comp (complement (set (keys parent-config-value)))
                                     second)
                               potential-keys)
        ;; only return keys with no errors on path if they are around
        high-potential-keys (filter (comp zero? first) potential-keys)
        potential-keys (distinct (map second (or (not-empty high-potential-keys) potential-keys)))]
    (when (not-empty potential-keys)
      {:Error :misspelled-key
       :key bad-key
       :corrections potential-keys
       :confidence :high})))

;; determining the best path for the existing config

(defn pred-key? [k]
  (and (keyword? k)
       (.startsWith (name k) "pred-key_")))

(defn predicate-for-pred-key [pred-key]
  (when-let [[k _ pred-fn] (first (schema-rules [:= pred-key]))]
    pred-fn))

(defn next-configs [config x]
  (cond
    (and (pred-key? x) (map? config))
    (when-let [pred-fn (predicate-for-pred-key x)]
      (keep (fn [[k v]] (when (pred-fn k) [k v])) config))
    (and (number? x) (zero? x) (sequential? config))
    (map vector (range) config)
    :else
    (when (get config x) [[x (get config x)]])))

(defn longest-path-into-config [config [x & xs :as path]]
  (when-not (and (coll? config) (empty? config) (empty? path))
    (first
     (sort-by
      (comp - count)
      (for [[k next-config] (not-empty (next-configs config x))]
        (cons k (longest-path-into-config next-config xs)))))))

(defn best-path [orig-config paths]
  (when-let [[path-pattern path]
             (first
              (sort-by
               (comp - count flatten)
               (filter #(apply not= %)
                       (map (juxt identity (partial longest-path-into-config orig-config)) paths))))]
    [path-pattern (concat path (drop (count path) path-pattern))]))

(defn misplaced-key? [root-type orig-config parent-typ parent-config-value bad-key]
  (let [potential-types (for [[ky _ [pt _ val-typ]] (schema-rules [:- bad-key])
                              :when (not= pt parent-typ)]
                          {:parent-type pt :child-type  val-typ})
        correct-path-types
        (filter
         (comp not-empty :correct-paths)
         (mapv
          #(assoc %
                  :correct-paths
                  (get-paths-for-key root-type (get % :child-type) bad-key))
          potential-types))
        child-analysis (detailed-config-analysis {bad-key (get parent-config-value bad-key)})
        correct-path-types
        (filter (fn [{:keys [parent-type]}]
                  (true? (analysis-type-matches parent-type child-analysis)))
                correct-path-types)
        [path-pattern best-fit-path] (best-path orig-config (mapcat :correct-paths correct-path-types))]
    (when path-pattern
      (let [{:keys [parent-type child-type]}
            (first (filter #((set (:correct-paths %)) path-pattern) correct-path-types))]
        {:Error :misplaced-key
         :key bad-key
         :correct-type [parent-type :> child-type]
         :correct-paths [best-fit-path]
         :confidence :high}))
    ))

;; TODO this is a duplicate of the above functionality
;; please take a look at this
;; more than likely can use a simple child analysis to determine this
;; info in one function
(defn misspelled-misplaced-key? [root-type orig-config parent-typ parent-config-value bad-key]
  (let [potential-types (for [[ky _ [pt _ val-typ]] (schema-rules :-)
                              :when (and
                                     (not= pt parent-typ)
                                     (fuz/similar-key 0 bad-key ky))]
                          {:parent-type pt :child-type  val-typ :new-key ky})
        correct-path-types
        (filter
         identity #_(comp not-empty :correct-paths)
         (mapv
          #(assoc %
                  :correct-paths
                  (get-paths-for-key root-type (get % :child-type) (get % :new-key)))
          potential-types))
        child-analysis-fn (fn [new-key]
                            (detailed-config-analysis {new-key (get parent-config-value bad-key)}))
        correct-path-types
        (filter (fn [{:keys [parent-type new-key]}]
                  (true? (analysis-type-matches parent-type (child-analysis-fn new-key))))
                correct-path-types)
        [path-pattern best-fit-path] (best-path orig-config (mapcat :correct-paths correct-path-types))]
    ;; TODO best path can't exist already!!
    ;; make sure that value matches type
    #_[bad-key potential-types correct-path-types best-fit-path ]
    #_(prn potential-types correct-path-types)
    (when path-pattern
      (let [{:keys [parent-type new-key child-type]}
            (first (filter #((set (:correct-paths %)) path-pattern) correct-path-types))]
        {:Error :misspelled-misplaced-key
         :key bad-key
         :correction new-key
         :correct-type [parent-type :> child-type]
         :correct-paths [best-fit-path]
         :confidence :high}))))

(defn miss-matches-type? [actual-type?]
  (and (not (true? actual-type?))
       (not (nil? (:alternate-type actual-type?)))))

(defn analyze-for-type-misplacement-helper [root-type type-sig path config collected-path]
  (when (not-empty type-sig)
    (let [analysis     (detailed-config-analysis config)
          actual-type? (analysis-type-matches (last type-sig) analysis)]
      #_(prn actual-type?)
      (if (miss-matches-type? actual-type?)
        {:Error :wrong-position-for-value
         :current-path collected-path
         :expected-type (last type-sig)
         :value config
         :path collected-path
         :actual-type (:alternate-type actual-type?)
         :correct-paths (get-paths-for-type root-type (:alternate-type actual-type?))}
        (let [k (last path)]
          (analyze-for-type-misplacement-helper
           root-type
           (butlast type-sig)
           (butlast path)
           (get config k)
           (cons k collected-path)))))))

(defn analyze-for-type-misplacement [root-type {:keys [type-sig path orig-config] :as error}]
  (analyze-for-type-misplacement-helper root-type type-sig path orig-config '()))

(defn unknown-key-error-helper [root-type parent-type bad-key value error]
  (let [parent-config-value (error-parent-value error)
        analysis            (detailed-config-analysis parent-config-value)
        actual-parent-type? (analysis-type-matches parent-type analysis)]
    (if-let [mispelled-key-error (misspelled-key? parent-type parent-config-value bad-key analysis)]
      [mispelled-key-error]
      (if-let [misplaced-key-error
               (misplaced-key? root-type
                               (get error :orig-config)
                               parent-type
                               parent-config-value
                               bad-key)]
        [misplaced-key-error]
        (if-let [misspelled-misplaced-key-error
                 (misspelled-misplaced-key? root-type
                                            (get error :orig-config)
                                            parent-type
                                            parent-config-value
                                            bad-key)]
          [misspelled-misplaced-key-error]
          [])))))

#_(with-schema (index-spec
                (spec 'Figwheel {:figwheel (ref-schema 'Boolean)
                                 :cljsbuild (ref-schema 'Boolean)})
                (spec 'Forest   {:figwheel (ref-schema 'Boolean)
                                  :cljs (ref-schema 'Boolean)})
                (spec 'Something {:something (ref-schema 'Figwheel)})
                (or-spec 'Boolean true false))
    (doall (type-check 'Something {:something
                                  {:figheeler 5
                                  :cljsbuild 5
                                  }})))

;; Printing out errors

(defmulti handle-type-error-groupp (fn [root-type [typ errors]] typ))

(defmethod  handle-type-error-groupp :default [root-type [typ errors]]
  (map #(assoc % :Error typ) errors))

(defmethod  handle-type-error-groupp :failed-predicate [root-type [typ errors]]
  (let [same-path-errors (group-by :path errors)]
    (mapcat (fn [[p err']]
              (if (> (count err') 1)
                [(assoc {:Error :combined-failed-predicate
                         :path (:path (first err'))
                         :value (:value (first err'))
                         :type-sig (:type-sig (first err'))
                         :not (mapv :not err')} :originals err')]
                [(assoc (first err') :Error :failed-predicate)]))
            same-path-errors))  )

(defmethod handle-type-error-groupp :unknown-key [root-type [typ errors]]
  (mapcat (fn [{:keys [key type-sig value] :as error}]
            (let [typ (first type-sig)
                  err' (unknown-key-error-helper root-type (first type-sig) key value error)]
              (if (empty? err')
                [(assoc error :Error (:Error-type error))]
                (map #(merge {:path (:path error)
                              :value (:value error)
                              :type-sig (:type-sig error)
                              :orig-error error}  %) err'))
              ))
        errors))

(defn type-check [root-type value]
  (if-let [results (not-empty (type-checker root-type value {}))]
    (let [res-groups (group-by :Error-type (map #(assoc % :orig-config value)
                                                results))]
      (doall
       (mapcat (fn [[typ errors]] (handle-type-error-groupp root-type [typ errors]))
           res-groups)))
    []))


(defn group-and-sort-first-pass-errors [errors]
  (let [res-groups (group-by :Error-type errors)
        order  [:deprecated-key
                :wrong-position-for-value
                :misspelled-key
                :misplaced-key 
                :misspelled-misplaced-key
                :unknown-key
                :missing-required-key
                :should-not-be-empty                
                :combined-failed-predicate                
                :failed-key-predicate
                :failed-predicate]]
    (sort-by (fn [[k v]] (let [pos (.indexOf order k)]
                          (if (neg? pos) 100 pos))) res-groups)))

;; I don't have deprecate functionality and I'm probably going to
;; be moving to clojure.spec soon so let's just punt on this one
(defn deprecate [errors]
  (let [missing-compiler      (first (filter #(and (= (:Error-type %) :missing-required-key)
                                                   (= (:key %) :compiler)) errors))
        unknown-build-options (first (filter #(and (= (:Error-type %) :unknown-key)
                                                   (= (:key %) :build-options)) errors))]
    (if (and missing-compiler unknown-build-options)
      [(assoc unknown-build-options
                :Error-type :deprecated-key
                :corrections [(:key missing-compiler)])]
      errors)))

(defn type-check-one-error [root-type value]
  (when-let [results (not-empty (type-checker root-type value {}))]
    (when-let [[[typ errors] & xs] (not-empty (->> results
                                                   (map #(assoc % :orig-config value))
                                                   deprecate
                                                   group-and-sort-first-pass-errors))]
      (let [errors (take 1 errors)]
        (when-let [errors (not-empty (doall (handle-type-error-groupp root-type [typ errors])))]
          (when-let [[[typ errors] & xs] (not-empty (->> errors
                                                         (map #(assoc % :orig-config value))
                                                         group-and-sort-first-pass-errors))]
            (first errors)))))))

#_(with-schema
  (index-spec
   (spec 'Thought number?)
   (spec 'Thoughter (ref-schema 'Thought))
   (spec 'Thing
         {:Asdf 3
          :GFSD 4
          :qwerta (ref-schema 'Thoughter)                
          :qwerty (ref-schema 'Thoughter)})
   (spec 'Bing
         {:Asdf 3
          :GFSD 5
          :qwert (ref-schema 'Thoughter)})
   (spec 'Topper
         {:bing (ref-schema 'Bing)}))
  (doall (type-check-one-error 'Topper {:bing {:Asdf 3
                                               :GSFD 5
}}))
  )

(defn predicate-rules-for-type [parent-type]
  (if parent-type
    (all-predicates parent-type)
    (mapcat schema-rules [:= :== :=>])))

(defn pred-type-help
  "Finds all possible "
  ([simple-exp parent-typ]
   (doall
    (for [[typ pred-type pred-op :as pred] (predicate-rules-for-type parent-typ)
          :when (apply-pred pred simple-exp)]
      [typ simple-exp])))
  ([simple-exp]
   (pred-type-help simple-exp nil)))

#_(with-schema
  (index-spec
   (spec 'H (ref-schema 'Hey))
   (spec 'Hey (ref-schema 'Now))
   (spec 'Now (ref-schema 'Nower))
   (spec 'Nower :asdf)
   (spec 'Nower3 keyword?))
    (pred-type-help :asdf 'H)
  #_(concrete-parent 'Nower)
  )

(defn pred-type [exp]
  (doall
   (for [[typ exp]  (pred-type-help exp)
         parent-typ (concrete-parent typ)]
     [parent-typ exp])))

(defn good-bad-ratio
  "analyze the ratio of good parts to bad parts of analyzed paths"
  [paths]
  (if (not-empty paths)
    (let [total (reduce + (map count paths))
          total-errors (reduce +
                               (map
                                (fn [p]
                                  (count
                                   (filter #(and (coll? %)
                                                 (or (vector? %) (seq? %)))
                                           p)))
                                paths))]
      (/ (float total-errors)
         (float total)))
    0))

(def tc-simple pred-type)

(declare tc-with-parent)

(defn tc-kv [ky vl]
  (if-let [res (not-empty
                (doall
                 (concat
                  (for [[_ _ [pt _ val-typ]] (schema-rules [:- ky])
                        parent-type (concrete-parent pt)
                        child-types (tc-with-parent val-typ vl)]
                    (concat [parent-type ky] child-types))
                  ;; keys that are mispelled locals
                  (for [[k _ [pt _ val-typ]] (schema-rules :-)
                        :when (and (not= k ky)
                                   (fuz/similar-key 0 k ky))
                        parent-type (concrete-parent pt)
                        child-types (tc-with-parent val-typ vl)]
                    (concat [parent-type [:subst ky k]] child-types))
                  ;; keys that have complex children that match
                  ;; this value
                  ;; this needs to be refactored as we are covering the
                  ;; same ground as above
                  ;; TODO make this conditional like keyword
                  ;; predicates
                  ;; BEFORE keyword predicates
                  (when (and (map? vl) (> (count vl) 0))
                    (for [[k _ [pt _ val-typ]] (schema-rules :-)
                          :when (and
                                 (not= k ky)
                                 (not (fuz/similar-key 0 k ky)))
                          parent-type (concrete-parent pt)]
                      (let [child-type-paths (filter #(= (first %) val-typ)
                                                     (tc-with-parent val-typ vl))]
                        (when (< (good-bad-ratio child-type-paths)
                                 (if (> (count vl) 1)
                                   0.5 0.3))
                          (mapcat #(concat [parent-type [:replace-key ky k]] %)
                                  child-type-paths))))))))]
    res
    ;; try keyword predicates expensive
    (doall 
     (for [[pred-id _ [pt _ val-typ]] (schema-rules :?-)
           parent-type (concrete-parent pt)
           :when (when-let [pred-func (last (first (schema-rules [:= pred-id])))]
                   (pred-func ky)) 
           child-types (tc-with-parent val-typ vl)]
       (concat [parent-type ky] child-types)))))

#_(pp)

#_(with-schema
  (index-spec
   (spec 'Thing {:asdf 3
                 :asdg 4
                 :asd 3})
   (spec 'Thinger {keyword? number?}))
  #_(doall (concrete-parent 'Thinger:pred-key_1518831777))
  (doall (tc-kv :asdasd 3)))

(defn tc-seq [ky vl]
  (for [[_ _ [pt _ val-typ]] (schema-rules [:- 0])
        parent-type (concrete-parent pt)
        child-types (tc-with-parent val-typ vl)]
    (concat [parent-type ky] child-types)))

(defn tc-empty-coll [exp]
  (cond
    (map? exp)
    (for [[t _ col-typ] (schema-rules :=>)
          :when (= col-typ :MAPP)]
      [t exp])
    (sequence-like? exp)
    (for [[t _ col-typ] (schema-rules :=>)
          :when (= col-typ :SEQQ)]
      [t exp])))

;; TODO empty case?
(defn tc-complex [exp]
  (cond
    (and (coll? exp) (empty? exp)) (tc-empty-coll exp)
    (map? exp) ;; TODO pred-current type
    (mapcat (fn [[k v]] (tc-kv k v)) (seq exp))
    (sequence-like? exp)
    (mapcat (fn [[k v]] (tc-kv k v)) (map vector (range) (seq exp)))))

(defn tc-analyze [exp]
  (distinct
   (filter
    not-empty
    (cond
      (or (map? exp) (sequence-like? exp))
      (tc-complex exp)
      (not (coll? exp))
      (tc-simple exp)))))


(defn tc-with-parent [parent-typ exp]
  (let [res (tc-analyze exp)
        descendents (set (cons parent-typ (descendent-typs parent-typ)))]
    (if-let [good-types (not-empty (doall (filter #(descendents (first %)) res)))]
      good-types
      (if (and (not-empty res) (not (coll? exp)))
        [[[:bad-terminal-value parent-typ exp res]]]
        [[[:wrong-type parent-typ exp res]]]))))

#_(with-schema
  (index-spec
  (spec 'Thought number?)
  (spec 'Thoughter (ref-schema 'Thought))
  (spec 'Thing
        {:Asdf 3
         :GFSD 4
         :qwerta (ref-schema 'Thoughter)                
         :qwerty (ref-schema 'Thoughter)})
  (spec 'Bing
        {:Asdf 3
         :GFSD 5
         :qwert (ref-schema 'Thoughter)})
  (or-spec 'Boolean
           true
           false)
  (or-spec 'Number
           1 2)
  (or-spec 'Happy
           {:hey 1}
           #_(ref-schema 'Boolean))
  (spec 'Topper
        {;:bing (ref-schema 'Bing)
         :bool (ref-schema 'Happy)})
  (spec 'TArr
        [{:bing (ref-schema 'Bing)}]))
  (tc** {:bool {:he 1}}
  #_{:hey 1}

 ))


;; Error messages

(defn parent-is-sequence? [{:keys [type-sig path]}]
  (and (integer? (first path))
       ((set (map last (schema-rules [:=> (second type-sig)]))) :SEQQ)))

(defmulti predicate-explain (fn [pred _] pred))

(defmethod predicate-explain :default [pred value]
  (if-let [typ (first (for [[t _ p] (schema-rules :=)
                            :when (= p pred)] t))]
    (name typ)
    pred))

(defmethod predicate-explain anything? [_ value] "Anything")
(defmethod predicate-explain integer? [_ value] "Integer")
(defmethod predicate-explain string? [_ value] "String")
(defmethod predicate-explain symbol? [_ value] "Symbol")
(defmethod predicate-explain number? [_ value] "Number")

(defmethod predicate-explain string-or-symbol? [_ value] "String or Symbol")

(defmethod predicate-explain keyword? [_ value] "Keyword")

(defmethod predicate-explain named? [_ value] "String, Keyword, or Symbol")

(defmulti explain-predicate-failure
  (fn [pred _] (if (fn? pred) ::pred-function pred)))

(defmethod explain-predicate-failure :default [pred value] (pr-str pred))
(defmethod explain-predicate-failure ::pred-function [pred value] (predicate-explain pred value))
(defmethod explain-predicate-failure :MAPP [pred value] "Map")
(defmethod explain-predicate-failure :SEQQ [pred value] "Vector")

(defmulti error-help-message :Error)

(defmethod error-help-message :unknown-key [{:keys [path key]}]
  [:group "Found unrecognized key "
   (color (pr-str key) :red)
   " at path "
   (color (pr-str (vec (reverse (rest path)))) :bold)])

(defmethod error-help-message :missing-required-key [{:keys [path key]}]
  [:group "Missing required key "
   (color (pr-str key) :bold)
   " at path "
   (color (pr-str (vec (reverse (rest path)))) :bold)]
  )

(defmethod error-help-message :should-not-be-empty [{:keys [path key]}]
  [:group "Key "
   (color (pr-str key) :bold)
   " at path "
   (color (pr-str (vec (reverse (rest path)))) :bold)
   " should not be empty."])

#_(pp)

(declare format-key summarize-value summarize-value-unwrap-top)

(defmethod error-help-message :failed-key-predicate [{:keys [key path not value type-sig] :as error}]
  [:group "The key "
   (format-key key :red)
   " did not satisfy the key predicate. "
   :line
   "It should be a "
    (color (explain-predicate-failure not value) :green)])

(defmethod error-help-message :failed-predicate [{:keys [path not value type-sig] :as error}]
  (concat
   (if (parent-is-sequence? error)
     [:group "The sequence at key " (color (pr-str (second path)) :bold)
      " contains bad value " (color (pr-str value) :red) ". "]
     [:group "The key "
      (color (pr-str (first path)) :bold)
      " has the wrong value. "])
   ["It should probably be a "
    (color (explain-predicate-failure not value) :green)]))

(defmethod error-help-message :combined-failed-predicate [{:keys [path not value] :as error}]
  (concat
   (if (parent-is-sequence? error)
     [:group "The sequence at key "  (format-key (second path) :bold)
     " contains bad value " (color (pr-str value) :red) ". "]
     [:group "The key "
      (format-key (first path) :bold)
      " has the wrong value. "
      ])
   [
    "It can one of the following: "
    (cons :span (interpose ", "
                           (map #(color % :green)
                                (map #(explain-predicate-failure % value) (butlast not)))))
    " or " (color (explain-predicate-failure (last not) value) :green)]))

#_(error-help-message {:Error :combined-failed-predicate :path [:boolean] :value 5 :not [true false]})

#_(pp)

(defmethod error-help-message :misspelled-key [{:keys [key corrections]}]
  [:group
   "The key "
   (color (pr-str key) :bold)
   " is spelled wrong. "
   (if (= 1 (count corrections))
     [:span "It should probably be " (color (pr-str (first corrections)) :green)]
     [:span "It could be one of: " (cons :span (interpose ", " (mapv #(color (pr-str %) :green) corrections)))])])

(defmethod error-help-message :deprecated-key [{:keys [key corrections]}]
  [:group
   "The key "
   (color (pr-str key) :bold)
   " is deprecated and no longer valid. "
   (if (= 1 (count corrections))
     [:span "Please use " (color (pr-str (first corrections)) :green)]
     [:span "It could be one of: " (cons :span (interpose ", " (mapv #(color (pr-str %) :green) corrections)))])])

(defmethod error-help-message :misplaced-key [{:keys [key correct-paths]}]
  [:group
   "The key "
   (color (pr-str key) :bold)
   " is most likely in the wrong place in your config."])

(defmethod error-help-message :misspelled-misplaced-key
  [{:keys [key correction correct-paths]}]
  [:group
   "The key "
   (color (pr-str key) :bold)
   " is spelled wrong and is mostly likely in the wrong position."
   :line " It should be probably be spelled "
   (color (pr-str correction) :green)]
  )

(defmethod error-help-message :wrong-position-for-value
  [{:keys [key correction correct-paths]}]
  [:group "The value below is most likely in the wrong place in your config."])

#_(pp)
;; printing

(defn format-map [leaf-node]
  [:group "{"
   [:nest 2
    :line
    leaf-node]
   "}"])

(defn print-path-helper [[x & xs :as path] leaf-node]
  (if (empty? path)
    leaf-node
    (let [array? (and (number? x) (zero? x))]
      [:group
       (if array? "[" "{")
       (if array?
         (print-path-helper xs leaf-node)
         [:nest 2
          :line
          (list
           (if array? "" [:span [:text (pr-str x)] " "])
           (print-path-helper xs leaf-node))])
       (if array? "]" "}")])))

;; print-path ignores the initial wrapping 
(defn print-path [[x & xs :as path] leaf-node]
  (if (empty? path)
    leaf-node
    (let [array? (and (number? x) (zero? x))]
      (if array?
      (print-path-helper xs leaf-node)
      [:group
       (if array? "" [:span [:text (pr-str x)] " "])
       (print-path-helper xs leaf-node)]))))



#_(fipp-format-data {:asdf {:Asdf {:asdf 1 :asdfff 2}}}) 


(print-path [] [:text "3"])
#_(pprint-document (print-path [:asdf 0 :asdfff :asdf 0 ] [:text "3"] )
                 {:width 20} )

(defn blank-document? [x]
  (or (nil? x)
      (and (string? x) (string/blank? x))
      (empty? x)))

(defn print-document [d]
  (if (blank-document? d)
    [:group :break :break]
    [:group :break d :line]))

(defn print-path-error [message path leaf-node document]
   [:group
    (color "\n------- Figwheel Configuration Error -------\n" :red)
    :break
    message
    :break
    :break
    [:nest 2 (print-path path leaf-node)]
    :break
    (print-document document)])

#_(pprint-document (print-path [:cljsbuild :builds 0 :compiler :closure-warnings :const] "5"
                             (gen-path [:cljsbuild :builds 0 :compiler :closure-warnings :const] "5"))
                 {:width 20})

#_(gen-path [:cljsbuild :builds 0 :compiler :closure-warnings :const] 5)

#_(pp)
(defn print-wrong-path-error [message path leaf-node document correct-path value]
     [:group
   (color "\n------- Figwheel Configuration Error -------\n" :red)
   :break
   message
   :break
   :break
   [:nest 2 
    (print-path path leaf-node)]
   :break
   :break
   [:group "It should probably be placed here:"]
   :break
   :break
   [:nest 2
    (print-path correct-path
                (color (if (empty? correct-path)
                         (summarize-value-unwrap-top value)
                         (summarize-value value))
                       :bold))]
   :break
   (print-document document)])

(defn type-doc [parent-type]
  (when-let [[_ _ d] (first (schema-rules [:doc-type parent-type]))]
    d))

(declare key-doc)

(defn find-key-doc [parent-type ky]
  (doall
   (concat
    (for [[_ _ [pt _ next-type]] (schema-rules [:- ky])
          :when (and pt (= pt parent-type))]
      (or (type-doc next-type)
          (first (find-key-doc next-type ky))))
    (for [[_ _ next-type]  (schema-rules [:-- parent-type])
          :when next-type]
      (or (type-doc next-type)
          (first (find-key-doc next-type ky)))))))

(defn key-doc [parent-type ky]
  (first (concat
          (for [[_ _ k d] (schema-rules [:doc-key parent-type])
                :when (and k (= ky k))]
            d)
          (find-key-doc parent-type ky))))

(defn docs-for [parent-type ky]
  (into {}
        (filter
         (fn [[k d]] d)
         [[:typ (type-doc parent-type)]
          [:ky  (key-doc parent-type ky)]])))

;; building examples from docs

(declare get-example construct-example)

(defn get-example-for-type [parent-type]
  (let [doc-body (type-doc parent-type)]
    (when (map? doc-body)
      (cond
        (contains? doc-body :example) (:example doc-body)
        (contains? doc-body :example-construct-from) (construct-example parent-type doc-body)
        :else nil))))

(defn get-example-for-key [parent-type key]
  (let [{:keys [ky]} (docs-for parent-type key)]
    (when ky
      (get-example parent-type key ky))))

(defn next-parent-type [parent-type key]
  (first (for [[k _ [pt _ next-type]] (schema-rules [:- key])
               :when (= pt parent-type)]
           next-type)))

(defn construct-example [parent-type {:keys [example-construct-from] :as ky}]
  (condp = (first example-construct-from)
    :CreateExampleMap
    (into {}
          (filter
           (complement (comp nil? second))
           (mapv (juxt identity
                       (partial get-example-for-key parent-type))
                 (rest example-construct-from))))
    :CreateExampleVector
    (let [typ (second example-construct-from)]
      [(get-example-for-type typ)])))

(defn get-example [parent-type k ky]
  (if (not (or (map? ky) (string? ky)))
    nil
    (cond
      (string? ky) nil
      (contains? ky :example) (let [example (:example ky)]
                      (if (fn? example) (example) example))
      (contains? ky :example-construct-from)
      (construct-example (next-parent-type parent-type k) ky)
      :else nil)))

(defn print-key-doc [parent-type k key-doc]
  (cond
    (string? key-doc) key-doc
    (map? key-doc)
    (if (or (contains? key-doc :example)
            (contains?  key-doc :example-construct-from))
      (let [example (get-example parent-type k key-doc)]
        [:group
         (color (:content key-doc) :underline) 
         :break
         :break
         [:nest 2
          (pr-str k)
          " "
          (fipp-format-data example)]
         ])
      (color (:content key-doc) :underline))
    :else ""))

(defn document-key [parent-type k]
  (let [{:keys [ky] :as p} (docs-for parent-type k)]
    (if ky
      [:group
       "-- Docs for key " (color (pr-str k) :bold) " --" 
       (print-key-doc parent-type k ky)
       :break]
      "")))

(defn summarize-coll [open close fn v]
  (let [res (vec (cons :align
                         (list
                          (concat
                           (interpose :line (take 3 (map fn v)))
                           (if (> (count v) 3) [:line "..."] [])))))]
    (vec
     (concat [:group open " "]
             (list res)
             (list " " close)))))

(declare summarize-value summarize-term)

(def summarize-map (partial summarize-coll "{" "}"
                            (fn [[k v']] [:group (summarize-value k)
                                         " " (summarize-term v')])))

(defn summer-seq [v] [:group (summarize-value v)])
(def summarize-vec (partial summarize-coll "[" "]" summer-seq))
(def summarize-seq (partial summarize-coll "(" ")" summer-seq))
(def summarize-set (partial summarize-coll "#{" "}" summer-seq))

(defn summarize-term [v]
  (cond
    (map? v)    "{ ... }"
    (vector? v) "[ ... ]"
    (set? v)    "#{ ... }"
    (seq? v)    "( ... )"
    :else (pr-str v)))

(defn summarize-value [v]
  (cond
    (map? v) (summarize-map v)
    (vector? v) (summarize-vec v)
    (set? v) (summarize-set v)
    (seq? v) (summarize-seq v)
    :else (pr-str v)))

(defn summarize-value-unwrap-top [v]
  (let [res (summarize-value v)]
    (if (map? v)
      (nth res 3)
      res)))

(defn format-key
  ([k] (pr-str k))
  ([k colr] (color (format-key k) colr)))

(defn format-value
  ([value] (summarize-value value))
  ([value colr] (color (format-value value) colr)))

(defn format-under-message [message]
  [:group
   [:line "                             "]
   (color message :underline :magenta)])

(defn format-key-value [ky fk fv message]
  [:group
   fk 
   " "
   [:nest (inc (count (pr-str ky))) fv]
   (format-under-message message)
   :line])

(defmulti print-error :Error)

(defmethod print-error :unknown-key [{:keys [key value path type-sig] :as error}]
  (let [parent-path (reverse (rest path))]
    (pprint-document (print-path-error (error-help-message error)
                                       parent-path
                                       ((if (not-empty parent-path) format-map identity)
                                        (format-key-value
                                         key
                                         (format-key key :red)
                                         (format-value value)
                                         (str "^ key " (pr-str key) " not recognized")))
                                       "")
                     {:width 40})))


(comment
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nah)})
                                (spec 'Nah {:a 3})
                                (requires-keys 'RootMap :wowza))
                    'RootMap {:asdf 2})
  )

(defmethod print-error :missing-required-key [{:keys [path value key type-sig] :as error}]
  (let [parent-path (-> path rest reverse)]
    (pprint-document
     (print-path-error (error-help-message error)
                       parent-path
                       ((if (not-empty parent-path) format-map identity)
                        [:group
                         (color (pr-str key) :red)
                         [:line "                              "]
                         (color (str "^ required key " (pr-str key) " is missing")
                                :underline :magenta)
                         :line])
                       (document-key (first type-sig) key))
                   {:width 40})))

(comment
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nah)})
                                (spec 'Nah {:a 3})
                                (requires-keys 'RootMap :wowza)
                                (requires-keys 'Nah :a))
                    'RootMap {:wowza {}})
  )

(defn failed-predicate [{:keys [path value type-sig orig-config] :as error}]
  (let [parent-path (-> path rest reverse)]
    (if (parent-is-sequence? error)
      (let [orig-config-seq (get-in orig-config parent-path)]
        (pprint-document (print-path-error (error-help-message error)
                                           parent-path
                                           [:nest 2
                                            
                                            (concat
                                             [:group #_(format-key (second path))
                                              " [ " ]
                                             [[:align 
                                               (cons :group
                                                     (interpose :line (map-indexed 
                                                                       #(if (= (first path) %1)
                                                                          [:group (color (pr-str %2) :red)
                                                                           (format-under-message "^ is a bad value")]
                                                                          (pr-str %2))
                                                                       orig-config-seq)
                                                                ))]]
                                             #_(format-value value :red)
                                             [" ] "])]
                                           (document-key (first (rest (rest type-sig))) (second path)))
                         {:width 40}))
      (pprint-document (print-path-error (error-help-message error)
                                         parent-path
                                         ((if (not-empty parent-path) format-map identity)
                                          (format-key-value
                                           (first path)
                                           (format-key (first path) :bold)
                                           (format-value value :red)
                                           (str "^ key " (pr-str (first path)) " has wrong value")))
                                         (document-key (first (rest type-sig)) (first path)))
                       {:width 40}))

    )
  )

(comment

  (print-one-error  (index-spec (spec 'RootMap {:wowza 3 #_(ref-schema 'Nah)})
                                (spec 'Nah {:a 3})
                                #_(requires-keys 'RootMap :wowza)
                                #_(requires-keys 'Nah :a))
                    'RootMap {:wowza {:a 4}})
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nah)})
                                (spec 'Nah [string?])
                                #_(requires-keys 'RootMap :wowza)
                                #_(requires-keys 'Nah :a))
                    'RootMap {:wowza ["Asdf" 1 "asdf" ]})
  )



(defmethod print-error :failed-predicate [error] (failed-predicate error))
(defmethod print-error :combined-failed-predicate [error] (failed-predicate error))

(defmethod print-error :should-not-be-empty [{:keys [path value type-sig orig-config] :as error}]
  (let [parent-path (-> path rest reverse)
        ky          (first path)]
    (pprint-document (print-path-error (error-help-message error)
                                       parent-path
                                       ((if (not-empty parent-path) format-map identity)
                                        (format-key-value
                                         ky
                                         (format-key ky :bold)
                                         (format-value value :red)
                                         (str "^ key " (pr-str ky) " should not be empty")))
                                       (document-key (first type-sig) ky))
                     {:width 40})))

(comment
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nah)})
                                (spec 'Nah {:a [string?]})
                                (assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap {:wowza {:a []}})
  )

(defmethod print-error :misplaced-key [{:keys [key path correct-paths correct-type value orig-error orig-config type-sig] :as error}]
  (let [parent-path (reverse (rest path))
        correct-path (first correct-paths)
        correct-key  (last correct-path)]
    (pprint-document
     (print-wrong-path-error
      (error-help-message error)
      parent-path
      ((if (not-empty parent-path) format-map identity)
       (format-key-value
        key
        (format-key key :red)
        (format-value value)
        (str "^ key " (format-key key) " is on the wrong path")))
      (document-key (first correct-type) key)
      correct-path
      value)
     {:width 40})))

(comment

  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nahbu)
                                                :asdf {}})
                                (spec 'Nah {:aasdfasdfasdf 1
                                            :basdfasdfasdf 2
                                            :casdfasdfasdf 3
                                            })
                                (spec 'Nahbu {:nah (ref-schema 'Nah)
                                              :bleh {}})
                                #_(assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap
                    {:wowza {:nah {
                                  :aasdfasdfasdf 1
                                  :basdfasdfasdf 2
                                  :bleh {:asfd 1}
                                  }}
                     })
  
  )

(defmethod print-error :misspelled-misplaced-key
  [{:keys [key correct-paths correction path value correct-type orig-error orig-config type-sig] :as error}]
  (let [parent-path (reverse (rest path))
        correct-path (first correct-paths)
        correct-key  correction]
    (pprint-document (print-wrong-path-error
                      (error-help-message error)
                      parent-path
                      ((if (not-empty parent-path) format-map identity)
                       (format-key-value
                        key
                        (format-key key :red)
                        (format-value value)
                        (str "^ key " (format-key key) " is mispelled and on the wrong path")))
                      (document-key (first correct-type) correction)
                      correct-path
                      value)
                     {:width 40})))

(comment
'WORK
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nahbu)
                                                :asdf {}})
                                (spec 'Nah {:aasdfasdfasdf 1
                                            :basdfasdfasdf 2
                                            :casdfasdfasdf 3
                                            })
                                (spec 'Nahbu {:nah (ref-schema 'Nah)
                                              :bleh {}})
                                #_(assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap
                    {:wowza {:nah {
                                  :aasdfasdfasdf 1
                                  :basdfasdfasdf 2
                                  :blehh {:asfd 1}
                                  }}
                     })


  
  )


(defmethod print-error :wrong-position-for-value
  [{:keys [key value path correct-paths correct-type orig-error orig-config type-sig] :as error}]
#_[message path leaf-node document correct-path value]
  (let [parent-path (reverse path)
        correct-path (first correct-paths)
        correct-key  (last correct-path)]
    (pprint-document
     (print-wrong-path-error
      (error-help-message error)
      parent-path
      [:align
       (list
        (format-value value :bold)
        [:line "                           "]
        (str "^ value is in the wrong place"))]
      (document-key (first correct-type) correct-key)
      correct-path
      value)
   {:width 40})))


(comment

  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nahbu)
                                                :asdf {}})
                                (spec 'Nah {:aasdfasdfasdf 1
                                            :basdfasdfasdf 2
                                            :casdfasdfasdf 3
                                            })
                                (spec 'Nahbu {:nah (ref-schema 'Nah)})
                                #_(assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap
                    {:asdf {:aasdfasdfasdf 1
                            :basdfasdfasdf 2
                            :casdfasdfasdf 3
                            }
                      })
  )



(defmethod print-error :failed-key-predicate [{:keys [key value orig-error orig-config type-sig path] :as error}]
  (let [parent-path (-> path rest reverse)]
    (pprint-document (print-path-error (error-help-message error)
                                       parent-path
                                       ((if (not-empty parent-path) format-map identity)
                                        (format-key-value
                                         key
                                         (format-key key :red)
                                         (format-value value)
                                         (str "^ key " (pr-str key) " failed key predicate")))
                                       ;; document-parent
                                       (document-key (first (rest type-sig)) (first (rest path))))
                     {:width 40})))

(comment
  (print-one-error  (index-spec (spec 'RootMap {integer? (ref-schema 'Nah)})
                                (spec 'Nah {string? [string?]})
                                #_(assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap {:w 1})
  )

(defn document-all [parent-typ all-keys]
  (doall
   (vec
   (cons :group
        (interpose :break
                   (map
                    (fn [ky]
                      (document-key parent-typ ky)) all-keys))))))

(defmethod print-error :misspelled-key [{:keys [key corrections orig-error value orig-config type-sig path] :as error}]
  (let [parent-path (-> path rest reverse)]
    (pprint-document (print-path-error (error-help-message error)
                                       parent-path
                                       ((if (not-empty parent-path) format-map identity)
                                        (format-key-value
                                         key
                                         (format-key key :red)
                                         (format-value value)
                                         (str "^ key " (pr-str key) " is spelled wrong")))
                                       (document-all (first type-sig) corrections))
                     {:width 40})))

(defmethod print-error :deprecated-key [{:keys [key corrections orig-error value orig-config type-sig path] :as error}]
  (let [parent-path (-> path rest reverse)]
    (pprint-document (print-path-error (error-help-message error)
                                       parent-path
                                       ((if (not-empty parent-path) format-map identity)
                                        (format-key-value
                                         key
                                         (format-key key :red)
                                         (format-value value)
                                         (str "^ key " (pr-str key) " should be " (pr-str (first corrections)))))
                                       (document-all (first type-sig) corrections))
                     {:width 40})))

(comment
  (print-one-error  (index-spec (spec 'RootMap {:wowza (ref-schema 'Nah)})
                                (spec 'Nah {:nada [string?]})
                                #_(assert-not-empty 'Nah :a)
                                #_(requires-keys 'Nah :a))
                    'RootMap {:wowza {:nad []}})
  )

(defn print-errors [rules root config]
  (with-schema rules
    (mapv #(do (print-error (assoc % :orig-config config)) %)
          (type-check root config))))

(defn print-one-error [rules root config]
  (with-schema rules
    (when-let [single-error (type-check-one-error root config)]
      (print-error (assoc single-error :orig-config config)) 
      single-error)))

(defn raise-one-error [rules root config]
  (with-schema rules
    (when-let [single-error (type-check-one-error root config)]
      (print-error (assoc single-error :orig-config config)) 
      single-error)))

(defn print-errors-test [config]
  (mapv #(print-error (assoc % :orig-config config))
        (type-check 'RootMap config)))

(defn print-errors-test-first [config]
  (mapv #(print-error (assoc % :orig-config config))
        (take 1 (type-check 'RootMap config))))

(defn pp []
  (with-schema (index-spec
                (spec 'RootMap {:figwheel (ref-schema 'FigOpts)
                                :crappers [(ref-schema 'HeyOpts)]
                                })

                (or-spec 'Boolean
                         true
                         false)
                (spec 'OtherType
                      {:master 4
                       :faster 2})
                (doc 'FigOpts "System level options for the figwheel clojure process"
                     {:five "What happens with the number 5"
                      :string "A String that describes a string"
                      :magical "How magical do you want to make this?"
                      :what    "Something what like is something goodness"
                      :boolean "A boolean that tells us whether bolleans are allowed"
                      :builds  "A map of builds"})
                (spec 'FigOpts
                      {:five 5
                       :builds  {string? 5}
                       :boolean (ref-schema 'Boolean)
                       :other   (ref-schema 'OtherType)
                       :string   string?
                       :what    [(ref-schema 'Boolean)] #_[string?]
                       :magical "asdf"
                       :magicall 5})
                (doc 'FigOpts "This is some doe"
                     {:magicall "This is sooo magical"
                      :magical  "This is magical as well"}
                 )
                (spec 'HeyOpts
                      {:whaaa 5
                       :yeah 6})
                (doc 'OtherType "Options that describe the behavior of Other types"
                     {:master "How amant things should we master"
                      :faster "The number of faster things"})
                (requires-keys 'OtherType :faster)
                )

    

  #_ (doall  (type-check 'RootMap {:figwheel {:five 6
                                            :string 'asdf
                                            :boolean 4
                                            :other {:master 4}
                                            :magicl "asdf"}
                                 :willywonka 4}))
    
  #_(print-errors-test {:figwheel {:five 6
                                 :string 'asdf
                                 :boolean 4
                                 :builds {5 5}
                                 :what ["resources/public/js/out"
                                        "resources/public/js/out"
                                        "resources/public/js/out"]
                                 :wowzer {:master 4
                                          :faster "asdf"
                                          }
                                 :magicl "asdf"}
                      #_:crappers #_[{:whaaa 5
                                  :yeah 6}
                                 {:whaaa 5
                                  :yeahs 6}]
                      :magicl "asdf"
                      
                      :willywonka 4})
    #_(first (type-check 'RootMap {:five 5
                                 :string "asdf"
                                 :boolean true
                                 :builds {"asdf" 5}
                                 :what [ true ]
                                 :other { :master 4
                                          :faster "asdf"}
                                 :magical "asdf"}))
    (print-errors-test-first {:five "asdf"
                              :string "asdf"
                              :boolean true
                              :builds {"asdf" 5}
                              :what [ true]
                              :other { :master 4
                                      :faster "asdf"}
                              :magical "asdf"})
    
  #_(doall (document-key 'FigOpts :other))
    
    )

  )

#_(pp)
