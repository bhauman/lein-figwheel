(ns figwheel-sidecar.config-check.type-check
  (:require
   [fipp.engine :refer [pprint-document]]
   [figwheel-sidecar.config-check.ansi :refer [color]]
   [clj-fuzzy.metrics :as metrics]
   [clojure.walk :as walk]
   [clojure.string :as string]
   [clojure.set :refer [difference]]
   [clojure.core.logic :as l]))

(def ^:dynamic *schema-rules* nil)

(defmacro with-schema [rules & body] 
  `(binding [*schema-rules* ~rules] ~@body))

(defn schema-rules [arg]
  (if *schema-rules*
    (*schema-rules* arg)
    (throw (Exception. "Type Check Schema is not bound! Please bind type-check/*schema-rules* and watch lazyiness, binding scope."))))

(defn db-query [q db]
  (fn [a]
    (l/to-stream
     (map #(l/unify a % q) db))))

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
  (and (or (seq? x) (vector? x))
       (= (first x) :MAPP)))

(defn list?? [x]
    (and (or (seq? x) (vector? x))
         (= (first x) :SEQQ)))

(defn empty?? [x]
  (and (or (map?? x)
           (list?? x))
       (empty? (rest x))))


(defn complex-value? [v]
  (or (list?? v) (map?? v) (fn? v) (-> v meta :ref)))

(def simple-value? (complement complex-value?))

(defn prep-key [k]
  (if (fn? k)
    (keyword (str "pred-key_" (hash k)))
    k))

(defn decompose [type-gen-fn node' s']
  (letfn [(handle-key-type [orig-key predicate-key-name node t]
            (if (fn? orig-key)
              [[predicate-key-name :?- [node :> t]]
               [predicate-key-name := orig-key]]
              [[orig-key :- [node :> t]]]))
          (decomp [node s]
            (if (nil? s)
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

#_(required-keys 'FigOpts :hello :goodbye)

(defn doc
  ([root type-doc kd]
   (cons [root :doc-type type-doc]
         (mapv (fn [[k d]] [root :doc-key k d]) kd)))
  ([root type-doc]
   (doc root type-doc [])))

#_(index-spec
 (doc 'FigOpts "This is a cool option"
      {
       :hello ":hello is needed to say hello"
       :good ":good is needed to say goodbye"})) 

;; Direct Implementation
;; this is still squirrely

(defn index-spec [& spc]
  (let [spc (distinct (apply concat spc))]
    (merge
     (group-by second spc)
     (group-by (fn [x] [:parent (second x) (first (nth x 2))]) (filter #(#{:?- :-} (second %)) spc))
     (group-by (juxt second first) spc))))

(defn fetch-pred [pred-type parent-type]
  (if-let [res (not-empty (map last (*schema-rules* [pred-type parent-type])))]
    [pred-type (first res) parent-type] ))

(defn leaf-pred? [parent-type]
  (or (fetch-pred :=  parent-type)
      (fetch-pred :== parent-type)
      (fetch-pred :=> parent-type)))

(defn all-types [parent-type]
  (let [res (map last (*schema-rules* [:-- parent-type]))]
    (distinct (cons parent-type (concat res (mapcat all-types res))))))

(defn all-predicates [parent-type]
  (keep leaf-pred? (all-types parent-type)))

#_(with-schema (index-spec (spec 'Integer integer?)
                         (spec 'AnotherInt string?)
                         (spec 'AnotherInt (ref-schema 'Integer))
                         (spec 'AndAnotherInt (ref-schema 'AnotherInt)))
  (doall (all-predicates 'AndAnotherInt))
  #_(leaf-pred? 'AnotherInt)
  #_(all-types 'AndAnotherInt)
  #_(all-types 'AndAnotherInt)
  #_(to-leaf-types 'AAnotherInt)
  #_(to-leaf-types 'AnotherInt)
  )

(defmulti apply-pred (fn [f v] (first f)))
(defmethod apply-pred :== [[_ pred] value] (= value pred))
(defmethod apply-pred := [[_ pred] value] (pred value))
(defmethod apply-pred :=> [[_ pred] value] (= (cond
                                                (map? value) :MAPP
                                                (sequence-like? value) :SEQQ
                                                :else :_____BAD)
                                              pred))

(defn type-check-pred [pred value state]
  (if-not (apply-pred pred value)
    (let [error {:Error-type :failed-predicate
                 :not (second pred)
                 :value value
                 :type-sig (:type-sig state)
                 :path     (:path state)}]
      [(if (not= (-> state :type-sig first)
                 (last pred))
         (assoc error :sub-type (last pred)) error)])
    {:success-type (last pred)}))

(defn type-check-value [parent-type value state]
  (if-let [preds (all-predicates parent-type)]
    (let [errors   (map #(type-check-pred % value state) preds)
          success? (filter map? errors)]
      (if (not-empty success?)
        {:success-types (map :success-type success?)}
        (apply concat errors)))
    (throw (Exception. (str "parent-type " parent-type "has no predicate.")))))

(defn compound-type? [parent-type]
  (#{:SEQQ :MAPP} (second (fetch-pred :=> parent-type))))

(defn get-types-from-key-parent [parent-type ky]
  (map (comp last last)
       (filter #(= parent-type (-> % last first)) (*schema-rules* [:- ky]))))

(declare type-checker)

(defn fix-key [k parent-value]
  (if (sequence-like? parent-value) 0 k))

(defn find-keyword-predicate [parent-type]
  (when-let [[pred-id _ [pt _ kt]] (first (*schema-rules* [:parent :?- parent-type]))]
    (when-let [pred-func (last (first (*schema-rules* [:= pred-id])))]
      [pred-func kt])))

;; this is what implements or
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
  (set (for [[t _ k]  (*schema-rules* [:requires-key parent-type])
                  :when (= t parent-type)]
              k)))

#_(index-spec (requires-keys 'Root :figwheel :hi))

#_(difference #{:a :b :c} #{:c})

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

(defn type-checker-help [parent-type value state]
  (if-not (compound-type? parent-type)
    []
    (let [f (partial mapcat
                     (fn [[k v]]
                       (type-check-key-value parent-type k v (assoc state :parent-value value))))]
      (cond
        (map? value)
        (concat (check-required-keys parent-type value state) (f value))
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

;; more sophisticated errors

(defn named? [x]
  (or (string? x) (instance? clojure.lang.Named x)))

(defn string-or-symbol? [x]
  (or (string? x) (symbol? x)))

(defn boolean? [x] (or (true? x) (false? x)))

(defn anything? [x] true)

#_(defn key-distance [k other-key]
  (metrics/dice (name k) (name other-key)) )

(defn key-distance [ky ky1]
  (let [d (metrics/dice (name ky) (name ky1))
        l (metrics/levenshtein (name ky) (name ky1))]
    (if (and (> d 0.4) (< l 5))
      (+ (- d 0.4)
         (/ (- 5 l) 10.0))
      0)))

#_(metrics/levenshtein "fihgweel" "figwheel")
#_(metrics/mra-comparison )

(defn similar-key [thresh k other-key]
  (and (and (named? k)
            (named? other-key))
       (> (key-distance k other-key)
          thresh)))

(defn complexity [c]
  (if (coll? c)
    (+ (if (map? c) (count c) 1)
       (reduce + (map complexity c)))
    1))

(defn value-checks-out? [parent-type value]
  (let [errors (type-checker parent-type value {})
        filtered-errors (filter #(contains? #{:misspelled-key :missing-required-key}
                                  (get % :Error-type))
                                errors)
        complex (complexity value)]
    (cond
      (empty? errors) complex
      (empty? filtered-errors) (/ complex 2.0)
      :else false)))

(defn parents-for-type [typ']
  (concat (not-empty
           (for [[ky _ [parent-type _ typ]] (*schema-rules* :-)
                 :when (= typ typ')]
             [ky parent-type]))
          (not-empty
           (apply concat
                  (for [[up-type _ typ] (*schema-rules* :--)
                        :when (= typ typ')]
                    (parents-for-type up-type))))))

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

; possible keys

(defn decendent-type [rules typ t]
  (l/fresh [child-type]
    (db-query [typ :-- child-type] (rules :--))
    (l/conde
     [(l/== t child-type)]
     [(decendent-type rules child-type t)])))

(defn keys-for-parent-type [rules typ ky kt]
  (l/fresh [ct ot]
    (l/conda
     [(db-query [ky :- [typ :> kt]] (rules :-))]
     [(decendent-type rules typ ct)
      (db-query [ky :- [ct :> kt]] (rules :-))])))

(defn find-keys-for-type [rules typ]
  (l/run* [q]
    (l/fresh [ky kt]
      (keys-for-parent-type rules typ ky kt)
      (l/== q [ky kt]))))

(defn decendent-types [rules typ]
  (l/run* [q]
    (decendent-type rules typ q)))

(defn error-parent-value [{:keys [path orig-config]}]
  (get-in orig-config (reverse (rest path))))


;; this could be improved by considering the type of the
;; current parent value by considering the parents other keys
(defn misspelled-key [parent-type bad-key value error]
  (take 1
   (sort-by
    #(-> % :distance-score -)
    (filter identity
     (for [[ky key-type] (find-keys-for-type *schema-rules* parent-type)
          :when (and
                 (not= bad-key ky)
                 (similar-key 0 ky bad-key)
                 ;; make sure key is not member of parent value
                 (not ((set (keys (error-parent-value error))) ky)))]
       (when-let [score (value-checks-out? key-type value)]
         {:Error :misspelled-key
          :key bad-key
          :correction ky
          :distance-score (+ score (key-distance bad-key ky))
          :confidence :high}))))))

(defn misplaced-key [root-type parent-type bad-key value]
  (let [parent-type-set
        (set (cons parent-type (decendent-types *schema-rules* parent-type)))]
    (for [[ky _ [other-parent-type _ typ]] (*schema-rules* [:- bad-key])
          :when (and
                 (not (parent-type-set other-parent-type))
                 (value-checks-out? typ value))]
      {:Error :misplaced-key
       :key bad-key
       :correct-type [other-parent-type :> typ]
       :correct-paths (get-paths-for-key root-type typ ky)
       :confidence :high})))

(defn misspelled-misplaced-key [root-type parent-type bad-key value]
  (let [parent-type-set
        (set (cons parent-type (decendent-types *schema-rules* parent-type)))]
    (sort-by
     #(-> % :distance-score -)
     (for [[ky _ [other-parent-type _ typ]] (*schema-rules* :-)
           :when (and
                  (not= bad-key ky)
                  (not (parent-type-set other-parent-type))
                  (similar-key 0 ky bad-key)
                  (value-checks-out? typ value))]
       {:Error :misspelled-misplaced-key
        :key bad-key
        :correction ky
        :distance-score (key-distance bad-key ky)
        :correct-type [other-parent-type :> typ]
        :correct-paths (get-paths-for-key root-type typ ky)
        :confidence :high}))))

(defn unknown-key-error-help [root-type parent-type bad-key value error]
  (first
   (filter
    not-empty
    [(misspelled-key parent-type bad-key value error)
     (misplaced-key  root-type parent-type bad-key value)
     (misspelled-misplaced-key root-type parent-type bad-key value)])))

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
                  err' (unknown-key-error-help root-type (first type-sig) key value error)]
              (if (empty? err')
                [(assoc error :Error (:Error-type error))]
                (map #(assoc %
                           :path (:path error)
                           :value (:value error)
                           :type-sig (:type-sig error)
                           :orig-error error) err'))
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

#_(with-schema (index-spec
                (spec 'Figwheel {:figwheel (ref-schema 'Boolean)
                                 :cljsbuild (ref-schema 'Boolean)})
                (or-spec 'Boolean true false))
    (doall (type-check 'Figwheel {:figheel true
                                  :cljsbuild 5})))

;; 

#_(defn apply-predicate [pred val]
  (l/project
   [pred]
   (l/pred val (fn [v] (boolean (apply-pred pred v))))))


;; spiking on yet another way of analyzing a configuration for errors

(defn predicate-rules-for-type [parent-type]
  (let [f (if parent-type #(vector % parent-type) identity)]
    (apply concat (mapv schema-rules (map f [:= :== :=>])))))

(defn pred-type-help**
  ([simple-exp parent-typ]
   (doall
    (for [[typ pred-type pred] (predicate-rules-for-type parent-typ)
          :when (apply-pred [pred-type pred] simple-exp)]
      [typ simple-exp])))
  ([simple-exp]
   (pred-type-help** simple-exp nil)))

(defn concrete-parent** [typ]
  (doall
   (distinct
    (or (not-empty
         (concat
          (for [[ky _ [_ _ ty]] (schema-rules :-)
                :when (= ty typ)]
            typ)
          (apply concat
                 (for [[pt _ ty] (schema-rules :--)
                       :when (= ty typ)]
                   (concrete-parent** pt)))))
        [typ]))))


#_(defn concrete-child** [typ]
  (doall
   (distinct
    (or (not-empty
         (concat
          (for [[ky _ [_ _ ty]] (schema-rules [:parent :- typ])]
            typ)
          (apply concat
                 (for [[pt _ ct] (schema-rules [:-- typ])]
                   (concrete-child** ct)))))
        [typ]))))

#_(with-schema
  (index-spec
   (spec 'H (ref-schema 'Hey))
   (spec 'Hey (ref-schema 'Now))
   (spec 'Now (ref-schema 'Nower))
   (spec 'Nower :asdf))
  (concrete-child** 'H)
  )

(defn concrete-parent [rules parent-type typ]
  (l/fresh [ky pt ignore]
    (l/conda
     [(l/conde
       [(l/== parent-type typ)
        (db-query [ky :- [ignore :> typ]] (rules :-))]
       [(db-query [pt :-- typ] (rules :--))
        (concrete-parent rules parent-type pt)])]
     [(l/== parent-type typ)])))

#_(l/run* [q]
  (concrete-parent
   (index-spec
    (spec 'H (ref-schema 'Hey))
    (spec 'Hey (ref-schema 'Now))
    (spec 'Now (ref-schema 'Nower))
    (spec 'Nower :asdf))
   'H q
   )
  )


(defn pred-type** [exp]
  (doall
   (for [[typ exp]  (pred-type-help** exp)
         parent-typ (concrete-parent** typ)]
     [parent-typ exp])))

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
            :GFSD 4
            :qwert (ref-schema 'Thoughter)})
     (spec 'Topper
           {:bing (ref-schema 'Bing)}))
  (doall (pred-type** 3))
  )

(defn pred-type [rules val parent-type typ-path]
  (l/fresh [typ]
    (pred-type* rules val typ)
    (l/== typ-path [val])
    (concrete-parent rules parent-type typ)))

(defn tc-simple [rules simple-exp typ typ-path]
  (pred-type rules simple-exp typ typ-path))

(def tc-simple** pred-type**)

(declare tc tc-proxy tc-with-parent**)

(defn similar-key** [k ky]
  (l/project
   [k]
   (l/project
    [ky]
    (l/== true (boolean
                (and (not= k ky)
                     (similar-key 0 k ky)))))))

(defn tc-kv [rules ky val pt typ-path]
  (l/fresh [try-key try-parent]
    (l/matche
     [typ-path]
     ([[k val-typ . xs]]
      (l/conde
       [(db-query [ky :- [try-parent :> val-typ]] (rules :-))
        (l/== ky k)
        (tc-proxy rules val val-typ xs)]
       [(db-query [try-key :- [try-parent :> val-typ]] (rules :-))
        (similar-key** try-key ky)
        (l/== [:subst try-key ky] k)
        (tc-proxy rules val val-typ xs)])
      (concrete-parent rules pt try-parent)))))

#_(defn find-keyword-predicate [parent-type]
    (when-let [[pred-id _ [pt _ kt]] (first (*schema-rules* [:parent :?- parent-type]))]
      (when-let [pred-func (last (first (*schema-rules* [:= pred-id])))]
        [pred-func kt])))

(defn tc-kv** [ky val]
  (if-let [res (not-empty
                (doall
                 (concat
                  (for [[_ _ [pt _ val-typ]] (schema-rules [:- ky])
                        parent-type (concrete-parent** pt)
                        child-types (tc-with-parent** val-typ val)]
                    (concat [parent-type ky] child-types))
                  (for [[k _ [pt _ val-typ]] (schema-rules :-)
                        :when (and (not= k ky)
                                   (similar-key 0 k ky))
                        parent-type (concrete-parent** pt)
                        child-types (tc-with-parent** val-typ val)]
                    (concat [parent-type [:subst ky k]] child-types)))))]
    res
    ;; try keyword predicates expensive
    (doall
     (for [[pred-id _ [pt _ val-typ]] (schema-rules :?-)
           parent-type (concrete-parent** pt)
           :when (when-let [pred-func (last (first (schema-rules [:= pred-id])))]
                   (pred-func ky)) 
           child-types (tc-with-parent** val-typ val)]
       (concat [parent-type ky] child-types)))))

#_(with-schema
  (index-spec
   (spec 'Thing {:asdf 3
                 :asdg 4
                 :asd 3})
   (spec 'Thinger {keyword? number?}))
  #_(doall (concrete-parent** 'Thinger:pred-key_1518831777))
  (doall (tc-kv** :asdasd 3)))

(defn tc-seq [rules ky val pt typ-path]
  (l/fresh [try-key try-parent]
    (l/matche
     [typ-path]
     ([[k val-typ . xs]]
      (db-query [0 :- [try-parent :> val-typ]] (rules :-))
      (l/== ky k)
      (tc rules val val-typ xs)
      (concrete-parent rules pt try-parent)))))

(defn tc-seq** [ky val]
  (for [[_ _ [pt _ val-typ]] (schema-rules [:- 0])
        parent-type (concrete-parent** pt)
        child-types (tc-with-parent** val-typ val)]
    (concat [parent-type ky] child-types)))

(defn p [v]
  (l/project [v]
           (do
             (prn v)
             l/succeed)))

(defn tc-complex [rules complex-exp pt typ-path]
  (l/project
   [complex-exp]
   (l/conde
    [(l/pred complex-exp (fn [e] (map? e)))
     (l/fresh [k v ignore]
       #_(pred-type rules complex-exp pt ignore)
       (l/membero [k v] (seq complex-exp))
       (l/project
        [k]
        (l/project
         [v]
         (tc-kv rules k v pt typ-path))))]
    [(l/pred complex-exp (fn [e] (sequence-like? e)))
     (l/fresh [k v ignore]
       (l/membero [k v] (mapv vector (range) (seq complex-exp)))
       #_(pred-type rules complex-exp pt ignore)
       (l/project
        [k]
        (l/project
         [v]
         (tc-seq rules k v pt typ-path)))
       )])))

(defn tc-complex** [exp]
  (cond
    (map? exp) ;; TODO pred-current type
    (mapcat (fn [[k v]] (tc-kv** k v)) (seq exp))
    (sequence-like? exp)
    (mapcat (fn [[k v]] (tc-kv** k v)) (map vector (range) (seq exp)))))

(defn tc [rules exp pt typ-path]
  (l/conde
   [(l/pred exp (fn [e] (or (map? e) (sequence-like? e))))
    (tc-complex rules exp pt typ-path)]
   [(l/pred exp (fn [e] (not (coll? e))))
    (tc-simple rules exp pt typ-path)]))

(defn tc** [exp]
  (cond
    (or (map? exp) (sequence-like? exp))
    (tc-complex** exp)
    (not (coll? exp))
    (tc-simple** exp)))

(defn tc-with-parent** [parent-typ exp]
  (let [res (tc** exp)]
    (if-let [good-types (not-empty (doall (filter #(= parent-typ (first %)) res)))]
      good-types
      (if (and (not-empty res) (not (coll? exp)))
        [[[:bad-terminal-value parent-typ exp res]]]
        [[[:wrong-type parent-typ exp res]]]))))

(with-schema
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



(defn tc-concrete [rules exp]
  (distinct
   (l/run* [q]
    (l/fresh [a b]
      (tc rules exp a b)
      (l/== q [a b])))))

(def ^:dynamic *tc* nil)

(defn tc-proxy [rules exp pt typ-path]
  (l/project
   [rules]
   (l/project
    [exp]
    (db-query [pt typ-path] (tc-concrete rules exp)))))





#_(defn tc-is [rules parent-type exp]
  (if-let [possbile-types (not-empty (tc-concrete rules exp))]
    (let [type-freqs (frequencies (map first possbile-types))]
      ;; if it doesn't contain parent type
      (prn type-freqs)
      (cond
        (not (type-freqs parent-type))
        [[[:wrong-type parent-type possbile-types] []]]
        #_(not= (type-freqs parent-type)
              (reduce max (vals type-freqs)))
        #_[[[:wrong-type parent-type possbile-types] []]]
        :else possbile-types))
    []))

#_(defn tc-proxy [rules exp suggest-parent-type act-parent-type typ-path]
  (l/project
   [suggest-parent-type]
   (l/project
    [exp]
    (l/conda
     [(l/== suggest-parent-type act-parent-type)
      (db-query [suggest-parent-type typ-path] (tc-is rules suggest-parent-type exp))]
     [(db-query [act-parent-type typ-path] (tc-is rules suggest-parent-type exp))]))))

(tc-concrete
 (index-spec
  (spec 'Blah [string?]))
 ["asfasd"])

(tc-concrete
 (index-spec
  ;(spec 'Thought number?)
  ;(spec 'Thoughter (ref-schema 'Thought))
  #_(spec 'Thing
        {:Asdf 3
         :GFSD 4
         :qwerta (ref-schema 'Thoughter)                
         :qwerty (ref-schema 'Thoughter)})
  #_(spec 'Bing
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
  #_(spec 'TArr
          [{:bing (ref-schema 'Bing)}]))

  {:bool {:hey 1}}
  #_{:hey 1}


 )

(l/run* [q]
  (concrete-parent
   (index-spec
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
           :bool (ref-schema 'Happy)}))
   q
   'Happy||0
   #_{:bool {:hey 1}}
   #_{:hey 1}
   
   
   )
  )



#_(time
 (tc-is
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
          :GFSD 4
          :qwert (ref-schema 'Thoughter)})
   (spec 'Topper
         {:bing (ref-schema 'Bing)}))
  
  'Topper
  {:bing {:Asdf 3
          :GFSD 4
          :qwerty 7}}
  
  ))


;; error messages
#_(pp)
(defn parent-is-sequence? [{:keys [type-sig path]}]
  (and (integer? (first path))
       ((set (map last (*schema-rules* [:=> (second type-sig)]))) :SEQQ)))

(defmulti predicate-explain (fn [pred _] pred))

(defmethod predicate-explain :default [pred value]
  (if-let [typ (first (for [[t _ p] (*schema-rules* :=)
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
(defmethod explain-predicate-failure :SEQQ [pred value] "Sequence")

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

#_(pp)

(declare format-key summerize-value)

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
   ["It should be a "
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

(defmethod error-help-message :misspelled-key [{:keys [key correction]}]
  [:group
   "The key "
   (color (pr-str key) :bold)
   " is spelled wrong. It should be "
   (color (pr-str correction) :green)])

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
;; printing

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

(defn print-path-error
  ([{:keys [path orig-config] :as error} leaf-node document]
   [:group
    "------- Figwheel Configuration Error -------"
    :break
    (error-help-message error)
    :break
    :break
    [:nest 2
     (print-path (reverse
                  (if (parent-is-sequence? error)
                    (rest (rest path))
                    (rest path)))
                 leaf-node
                 orig-config)]
    :break
    :break
    document
    :line
    ])
  ([error leaf]
   (print-path-error error leaf "")))

(defn gen-path [path value]
  (if (empty? path)
    value
    (let [[x & xs] path]
      (cond
        (and (integer? x) (zero? x))
        [(gen-path xs value)]
        :else {x (gen-path xs value)}))))

(gen-path [:cljsbuild :builds 0 :compiler :closure-warnings :const] 5)

(defn print-wrong-path-error
  ([{:keys [path orig-config correction path correct-paths value] :as error} leaf-node document]
   [:group
    "------- Figwheel Configuration Error -------"
    :break
    (error-help-message error)
    :break
    :break
    [:nest 2
     (print-path (reverse (rest path))
                 leaf-node
                 orig-config)]
    :break
    :break
    [:group "It should probably be placed here:"]
    :break
    :break
    [:nest 2
     (let [k (or correction (first path))]
       (print-path (butlast (first correct-paths))
                   [:group
                    (color (pr-str k) :green)
                    " "
                    [:nest (+ (count (pr-str k)) 2)
                     (summerize-value value)]
                    :line]
                   (gen-path (first correct-paths) value)))]
    :break
    :break
    document
    :line
    ])
  ([error leaf]
   (print-path-error error leaf "")))

#_(pp)

;; Todos

;; handle printing :correct-path with knowledge of available config
;;     esp. for bad path errors

;; make coloring conditional

;; move current enum functions into type system in config_validation
;; look at adding smart documentation fns
;; conditional predicate i.e. conditional on neighbor or sister config parameters
;; tighten up unknown-key errors to include information about current parent config

(defn type-doc [parent-type]
  (when-let [[_ _ d] (first (*schema-rules* [:doc-type parent-type]))]
    d))

(declare key-doc)

(defn find-key-doc [parent-type ky]
  (doall
   (concat
    (for [[_ _ [pt _ next-type]] (*schema-rules* [:- ky])
          :when (and pt (= pt parent-type))]
      (or (type-doc next-type)
          (first (find-key-doc next-type ky))))
    (for [[_ _ next-type]  (*schema-rules* [:-- parent-type])
          :when next-type]
      (or (type-doc next-type)
          (first (find-key-doc next-type ky)))))))

(defn key-doc [parent-type ky]
  (first (concat
          (for [[_ _ k d] (*schema-rules* [:doc-key parent-type])
                :when (and k (= ky k))]
            d)
          (find-key-doc parent-type ky))))

(defn docs-for [parent-type ky]
  (into {}
        (filter
         (fn [[k d]] d)
         [[:typ (type-doc parent-type)]
          [:ky  (key-doc parent-type ky)]])))

#_(with-schema (index-spec
              (concat
               (doc 'Root "The root doc"
                    {:fan "How many fans do you have."})
               (spec 'Boot
                     {:root (ref-schema 'Rap)}
                     )
               (spec 'Rap (ref-schema 'Batt))
               (spec 'Batt (ref-schema 'Root)))
              )
  (type-doc 'Root)
  (key-doc 'Root :fan)
  (docs-for 'Boot :root)
  #_(key-doc 'Boot :root)

  #_(doall (*schema-rules* [:doc-key 'Rap]))
  )

(defn document-key [parent-type k]
  (let [{:keys [ky] :as p} (docs-for parent-type k)]
    (if ky
      [:group
       "-- Docs for key " (color (pr-str k) :bold) " --" 
       :break
       (color ky
                       :underline)
       :break]
      "")))

(defn summerize-coll [open close fn v]
  (let [res (vec (concat [:nest (inc (count open))] (interpose :line (take 2 (map fn v)))))]
    (vec
     (concat
      [:group open " "]
      [(vec
        (concat res
                (if (> (count v) 2) [:line "... " close] [" " close]))) ]
      ))))

(declare summerize-value summerize-term)

(def summerize-map (partial summerize-coll "{" "}"
                            (fn [[k v']] [:group (summerize-value k)
                                         " " (summerize-term v')])))

(defn summer-seq [v] [:group (summerize-value v)])
(def summerize-vec (partial summerize-coll "[" "]" summer-seq))
(def summerize-seq (partial summerize-coll "(" ")" summer-seq))
(def summerize-set (partial summerize-coll "#{" "}" summer-seq))

#_(summerize-map {:asdf 4 :asd 6})

(defn summerize-term [v]
  (cond
    (map? v)    "{ ... }"
    (vector? v) "[ ... ]"
    (set? v)    "#{ ... }"
    (seq? v)    "( ... )"
    :else (pr-str v)))

(defn summerize-value [v]
  (cond
    (map? v) (summerize-map v)
    (vector? v) (summerize-vec v)
    (set? v) (summerize-set v)
    (seq? v) (summerize-seq v)
    :else (pr-str v)))

#_ (pp)

(defn format-key
  ([k] (pr-str k))
  ([k colr] (color (format-key k) colr)))

(defn format-value
  ([value] (summerize-value value))
  ([value colr] (color (format-value value) colr)))

(defn format-key-value [ky fk fv message]
  [:group
   fk 
   " "
   [:nest (inc (count (pr-str ky))) fv]
   [:line " <- "]
    (color message :underline :magenta)
    :line])

(defmulti print-error :Error)

(defmethod print-error :unknown-key [{:keys [key value path type-sig] :as error}]
  (pprint-document (print-path-error error
                                     (format-key-value
                                      key
                                      (format-key key :red)
                                      (format-value value)
                                      (str "^ key " (pr-str key) " not recognized")))
                   {:width 40}))

#_(pp)
(defmethod print-error :missing-required-key [{:keys [path value type-sig] :as error}]
  (pprint-document (print-path-error error
                                     [:group
                                      (color (pr-str (first path)) :red)
                                      [:line " <- "]
                                      (color (str "^ required key " (pr-str (first path)) " is missing")
                                             :underline :magenta)
                                      :line]
                                     (document-key (first type-sig) (first path))
                                     )
                   {:width 40}))

#_(pp)



(defn failed-predicate [{:keys [path value type-sig orig-config] :as error}]
  (if (parent-is-sequence? error)
    (let [orig-config-seq (get-in orig-config (reverse (rest path)))]
      (pprint-document (print-path-error error 
                                         [:nest 2

                                          (concat
                                           [:group (format-key (second path))  " [ " ]
                                           [[:nest (+ 3 (count (str (second path))))
                                             
                                             (cons :group
                                                   (interpose :line (map-indexed 
                                                             #(if (= (first path) %1)
                                                                (color (pr-str %2) :red)
                                                                (pr-str %2))
                                                             orig-config-seq)
                                                      ))]]
                                           #_(format-value value :red)
                                           [" ] "])]
                                         (document-key (first (rest (rest type-sig))) (second path)))
                       {:width 40}))
    (pprint-document (print-path-error error
                                       (format-key-value
                                        (first path)
                                        (format-key (first path) :bold)
                                        (format-value value :red)
                                        (str "^ key " (pr-str (first path)) " has wrong value"))
                                       (document-key (first (rest type-sig)) (first path)))
                     {:width 40}))
  )

(defmethod print-error :failed-predicate [error] (failed-predicate error))
(defmethod print-error :combined-failed-predicate [error] (failed-predicate error))

#_(pp)
(defmethod print-error :misplaced-key [{:keys [key correct-paths correct-type orig-error orig-config type-sig] :as error}]
  (pprint-document (print-wrong-path-error
                    error
                    (format-key-value
                     key
                     (format-key key :red)
                     (format-value (:value orig-error))
                     (str "^ key " (format-key key) " is on the wrong path"))
                    (document-key (first correct-type) key))
                   {:width 40}))

(defmethod print-error :misspelled-misplaced-key [{:keys [key correct-paths correction correct-type orig-error orig-config type-sig] :as error}]
  (pprint-document (print-wrong-path-error
                    error
                    (format-key-value
                     key
                     (format-key key :red)
                     (format-value (:value orig-error))
                     (str "^ key " (format-key key) " is mispelled and on the wrong path"))
                    (document-key (first correct-type) correction))
                   {:width 40}))

(defmethod print-error :failed-key-predicate [{:keys [key value orig-error orig-config type-sig path] :as error}]
  (pprint-document (print-path-error error
                                     (format-key-value
                                      key
                                      (format-key key :red)
                                      (format-value value)
                                      (str "^ key " (pr-str key) " failed key predicate"))
                                     (document-key (first (rest type-sig)) (first (rest path))))
                   {:width 40}))
#_(pp)
(defmethod print-error :misspelled-key [{:keys [key correction orig-error orig-config type-sig] :as error}]
  (pprint-document (print-path-error error
                                     (format-key-value
                                      key
                                      (format-key key :red)
                                      (format-value (:value orig-error))
                                      (str "^ key " (pr-str key) " is spelled wrong"))
                                     (document-key (first type-sig) correction))
                   {:width 40}))

(defn print-errors [rules root config]
  (with-schema rules
    (mapv #(do (print-error (assoc % :orig-config config)) %)
          (type-check root config))))

(defn print-errors-test [config]
  #_(type-check 'RootMap config)
  (mapv #(print-error (assoc % :orig-config config))
        (type-check 'RootMap config)))

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
                       :magical "asdf"})
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
    
  (print-errors-test {:figwheel {:five 6
                                 :string 'asdf
                                 :boolean 4
                                 :builds {5 5}
                                 :what ["resources/public/js/out"
                                        "resources/public/js/out"
                                        "resources/public/js/out"]
                                 :other {:master 4}
                                 :magicl "asdf"}
                      #_:crappers #_[{:whaaa 5
                                  :yeah 6}
                                 {:whaaa 5
                                  :yeahs 6}]
                      :magicl "asdf"
                      
                      :willywonka 4})

  #_(doall (document-key 'FigOpts :other))
    
    )

  )

#_(pp)



#_(with-schema (index-spec (spec 'Root {:figwheel string?
                                      :forest integer?
                                      :other {:thing 5}}))
  (doall (unknown-key-error-help 'Root 'Root :fighweel "asdf"))
  (doall (unknown-key-error-help 'Root 'Root:other :figwheel "asdf"))
  #_(doall (misspelled-misplaced-key 'Root 'Root :thinge 5))
  )



#_(defn unknown-key-error [schema-rules parent-type typ bad-key config-val result]
  ;; check error conditions in order
  (l/conda
   ;; first error is where this key is mispelled and the config below
   ;; it checks out 
   [(l/fresh [k rt rk]
      (db-query [k :- [parent-type :> typ]]  (:- schema-rules))
      (key-like 0.4 bad-key k)
      ;; even more sophisicated if the config below only has
      ;; spelling errors !!:mind-blown:!!
      #_(type-check schema-rules config-val typ rt rk [])
      (pass-type-check? schema-rules typ config-val)
      (l/== result [:Error :mispelled-key :key bad-key :correction k :confidence :high]))]
   ;; second error is if key is suppossed to be located somewhere else
   [(l/fresh [other-parent-type corrected-path rt rk]
      (db-query [bad-key :- [other-parent-type :> typ]] (:- schema-rules))
      ;; no errors below
      (pass-type-check? schema-rules typ config-val)
      #_(type-check schema-rules config-val typ rt rk [])
      (path-for-type schema-rules typ corrected-path)
      (l/== result [:Error :misplaced-key :key bad-key :correct-type [other-parent-type :> typ]
                    :correct-path corrected-path
                    :confidence :high]))]
   ;; now its possible to be a mispelled global with correct chilren
   [(l/fresh [other-parent-type corrected-path rt rk potential-key]
      (db-query [potential-key :- [other-parent-type :> typ]] (:- schema-rules))
      (key-like 0.4 bad-key potential-key)
      ;; no errors below
      (pass-type-check? schema-rules typ config-val)
      #_(type-check schema-rules config-val typ rt rk [])
      (path-for-type schema-rules typ corrected-path)
      (l/== result [:Error :mispelled-and-misplaced-key :key bad-key :correct-type [other-parent-type :> typ]
                    :correct-path corrected-path
                    :confidence :high]))]
   ;; is there a key that most likely should be the name of this?
   [(l/fresh [rt rk other-type potential-key]
      (complex-config? config-val)
      (db-query [potential-key :- [parent-type :> typ]] (:- schema-rules))
      (pass-type-check? schema-rules typ config-val)
      (l/== result [:Error :wrong-key-used :key bad-key :correct-key potential-key
                    :confidence :high]))]
   ;; does this exact config belong somewhere else?
   
   ;; now we can consider the cases where the types don't checkout below
   ;; most likely its a mispelled local
   [(l/fresh [k]
      (db-query [k :- [parent-type :> typ]]  (:- schema-rules))
      (key-like 0.6 bad-key k) ;; perhaps raise spelling requirement
      (l/== result [:Error :mispelled-key :key bad-key :correction k :confidence :low]))]
   ;; misplaced global
   [(l/fresh [other-parent-type corrected-path]
      (db-query [bad-key :- [other-parent-type :> typ]] (:- schema-rules))
      (path-for-type schema-rules typ corrected-path)
      (l/== result [:Error :misplaced-key :key bad-key :correct-type [other-parent-type :> typ]
                    :correct-path corrected-path
                    :confidence :low]))]
   ;; misplaced mispelled
   [(l/fresh [other-parent-type corrected-path potential-key]
      (db-query [potential-key :- [other-parent-type :> typ]] (:- schema-rules))
      (key-like 0.7 bad-key potential-key) ;; perhaps raise spelling requirement
      (path-for-type schema-rules typ corrected-path)
      (l/== result [:Error :mispelled-and-misplaced-key :key bad-key :correct-type [other-parent-type :> typ]
                    :correct-path corrected-path
                    :confidence :low]))]
   
   [#_(l/== typ :unknown-type)
    (l/== result [:Error :unknown-key :val bad-key])]))
