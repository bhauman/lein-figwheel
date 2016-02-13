(ns figwheel-sidecar.type-check
  (:require
   [clj-fuzzy.metrics :as metrics]
   [clojure.walk :as walk]
   [clojure.core.logic :as l]))

(def ^:dynamic *schema-rules* nil)

(defmacro with-schema [rules & body] 
  `(binding [*schema-rules* ~rules] ~@body))

(defn db-query [q db]
  (fn [a]
    (l/to-stream
     (map #(l/unify a % q) db))))

(defn seqify [coll]
  (cond
    (map? coll)
    (cons :MAPP
          (map (fn [[a b]] [(seqify a) (seqify b)]) coll))
    (or (vector? coll)
        (list? coll))
    (cons :SEQQ (map vector (range) (map seqify coll)))
    :else coll))

(defn prep-key [k]
  (if (fn? k)
    (keyword (str "pred-key_" (hash k)))
    k))

(defn handle-key-type [orig-key predicate-key-name node t]
  (if (fn? orig-key)
    [[predicate-key-name :?- [node :> t]]
     [predicate-key-name :k= orig-key]]
    [[orig-key :- [node :> t]]]))

(defn decompose [type-gen-fn node' s']
  (letfn [(decomp [node s]
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

(defn spec [type & body]
  (->> body
       (map #(#'decompose type-gen type (seqify %)) )
       (apply concat)
       distinct))

;; Direct Implementation
;; this is still squirrely

(defn index-spec [& spc]
  (let [spc (distinct (apply concat spc))]
    (merge
     (group-by second spc)
     (group-by (fn [x] [:parent (second x) (first (nth x 2))]) (filter #(#{:?- :-} (second %)) spc))
     (group-by (juxt second first) spc))))

(defn fetch-pred [pred-type parent-type]
  (when-let [res (first (map last (*schema-rules* [pred-type parent-type])))]
    [pred-type res parent-type]))

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
                                                (seq? value) :SEQQ
                                                (vector? value) :SEQQ
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
         (assoc error :sub-type (last pred))   error)])
    {:success-type (last pred)}))

(defn type-check-value [parent-type value state]
  (if-let [preds (all-predicates parent-type)]
    (let [errors   (map #(type-check-pred % value state) preds)
          success? (first (filter map? errors))]
      (if success? success? (apply concat errors)))
    (throw (Exception. (str "parent-type " parent-type "has no predicate.")))))

(defn compound-type? [parent-type]
  (#{:SEQQ :MAPP} (second (fetch-pred :=> parent-type))))

(defn get-types-from-key-parent [parent-type ky]
  (map (comp last last)
       (filter #(= parent-type (-> % last first)) (*schema-rules* [:- ky]))))

(declare type-checker)

(defn fix-key [k parent-value]
  (if (or (vector? parent-value) (seq? parent-value)) 0 k))

(defn find-keyword-predicate [parent-type]
  (when-let [[pred-id _ [pt _ kt]] (first (*schema-rules* [:parent :?- parent-type]))]
    (when-let [pred-func (last (first (*schema-rules* [:k= pred-id])))]
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

(defn type-checker-help [parent-type value state]
  (if-not (compound-type? parent-type)
    []
    (let [f (partial mapcat
                     (fn [[k v]]
                       (type-check-key-value parent-type k v (assoc state :parent-value value))))]
      (cond
        (map? value)                      (f value)
        (or (vector? value) (seq? value)) (f (map vector (range) value))
        :else (throw (Exception. (str "Expected compound type: " (class value)
                                      " is not a Map, Vector, or Sequence")))))))

(defn type-checker [parent-type value state]
  (let [state (update-in state [:type-sig] conj parent-type)]
    (let [res (type-check-value parent-type value state)]
      (if (and (map? res) (:success-type res))
        (type-checker-help (:success-type res) value state)        
        res))))

(def tttt (index-spec
           (spec 'RootMap
                 {:figwheel (ref-schema 'FigOpts)
                  :cljsbuild (ref-schema 'CljsbuildOpts)})
           (spec 'FigOpts
                 {:server-port integer?
                  :server-ip   string?})
           (spec 'CljsbuildOpts
                 {:source-paths [string?]
                  :output-dir   string?})
           ))



(defn named? [x]
  (or (string? x) (instance? clojure.lang.Named x)))

(defn similar-key [thresh k other-key]
  (and (and (named? k)
            (named? other-key))
       (> (metrics/dice (name k)
                        (name other-key))
          thresh)))

(defn value-checks-out? [parent-type value]
  (empty? (type-checker parent-type value {})))

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

(defn misspelled-key [parent-type bad-key value]
  (for [[ky _ [_ _ typ]] (*schema-rules* [:parent :- parent-type])
        :when (and
               (not= bad-key ky)
               (similar-key 0.4 ky bad-key)
               (value-checks-out? typ value))]
    {:Error :mispelled-key
     :key bad-key
     :correction ky
     :confidence :high}))

(defn misplaced-key [root-type parent-type bad-key value]
  (for [[ky _ [other-parent-type _ typ]] (*schema-rules* [:- bad-key])
        :when (and
               (not= other-parent-type parent-type)
               (value-checks-out? typ value))]
    {:Error :misplaced-key
     :key bad-key
     :correct-type [other-parent-type :> typ]
     :correct-paths (get-paths-for-type root-type typ)
     :confidence :high}))


(defn misspelled-misplaced-key [root-type parent-type bad-key value]
  (for [[ky _ [other-parent-type _ typ]] (*schema-rules* :-)
        :when (and
               (not= bad-key ky)
               (not= other-parent-type parent-type)
               (similar-key 0.4 ky bad-key)
               (value-checks-out? typ value))]
    {:Error :misspelled-misplaced-key
     :key bad-key
     :correction ky
     :correct-type [other-parent-type :> typ]
     :correct-paths (get-paths-for-type root-type typ)
     :confidence :high}))

(defn unknown-key-error-help [root-type parent-type bad-key value]
  (first
   (filter
    not-empty
    [(misspelled-key parent-type bad-key value)
     (misplaced-key  root-type parent-type bad-key value)
     (misspelled-misplaced-key root-type parent-type bad-key value)])))



#_(with-schema (index-spec (spec 'Root {:figwheel string?
                                      :forest integer?
                                      :other {:thing 5}}))
  (doall (unknown-key-error-help 'Root 'Root :fighweel "asdf"))
  (doall (unknown-key-error-help 'Root 'Root:other :figwheel "asdf"))
  (doall (misspelled-misplaced-key 'Root 'Root :thinge 5))
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
