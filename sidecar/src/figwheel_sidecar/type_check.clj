(ns figwheel-sidecar.type-check
  (:require
   [clj-fuzzy.metrics :as metrics]
   [clojure.walk :as walk]
   [alandipert.intension :refer [make-db]]
   [datascript.core      :refer [q]]
   [clojure.core.logic :as l]
   [clojure.core.logic.pldb :as pldb]
   [clojure.test :as t :refer [deftest is run-tests]]))

(def ^:dynamic *schema-rules* [])

(defn db-query [q db]
  (fn [a]
    (l/to-stream
     (map #(l/unify a % q) db))))

(def schema {:blah {:borh 5}
             :root {:cljsbuild
                    {:other-crap string?
                     :builds integer?}}})

(defn seqify [coll]
  (cond
    (map? coll)
    (cons :MAPP
          (map (fn [[a b]] [(seqify a) (seqify b)]) coll))
    (or (vector? coll)
        (list? coll))
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

(defn un-seqify [coll]
  (if (or (map?? coll) (list?? coll))
    (condp = (first coll)
      :MAPP (into {} (map (fn [[k v]] [k (un-seqify v)]) (rest coll)))
      :SEQQ (into [] (map (fn [[k v]] (un-seqify v)) (rest coll))))
    coll))

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
      (fn? 's)
      [[node' := s']]
      :else
      [[node' :== s']])))

(defn ref-schema [s]
  (vary-meta s #(assoc % :ref true)))

(defn type-gen [node k]
  (symbol (str (name node) k)))

(defn spec [type body]
  (#'decompose type-gen type (seqify body)))

(comment
  (spec 'Hey {integer? :hey})
   
  (spec 'Hey {:asdf {:hey 5
                     :something (ref-schema 'BadAss)}})

  (spec 'Hey {:asdf {string? 5}})

  (def parse-rules    (comp (partial decompose #(gensym 'type) :RootMap) seqify))
  (def analyze-config (comp (partial decompose (fn [_] (l/lvar)) :RootMap) seqify))
    (spec 'RootMap
        {:cljsbuild 'CljsBuildOptions
         :welwel [integer?]
         :figwheel  'FigwheelOptions})
  )

(def grammer
  (concat
   (spec 'RootMap
         {:cljsbuild (ref-schema 'CljsBuildOptions)
          :figwheel  (ref-schema 'FigwheelOptions)})
   (spec 'CljsBuildOptions
         {:repl-listen-port integer?
          :crossovers       integer?})
   (spec 'FigwheelOptions
         {:server-port integer?
          :server-ip   string?})))

#_(spec 'RootMap
        {:figwheel {:server-port 5}
         :cljsbuild [{:server-ip "asdf"}]})

;; type inference
; type infer a tree
;; type check a tree

(l/defna listo [arg]
  ([[]])
  ([[x . _]]))

;; mising key error
;; consider consolidating root-type into tyep sig - with a helper?

(defn type-check-val [schema-rules parent-type value
                      errors-in
                      errors-out]
  (l/project
   [parent-type]
   (l/fresh [pred?]
     (l/conde
      [(db-query [parent-type := pred?] (schema-rules [:= parent-type]))
       (l/conda
        [(l/project [pred?] (l/pred value pred?))
         (l/== errors-out errors-in)]
        [(l/conso [:Error value :not pred?] errors-in errors-out)])]
      [(db-query [parent-type :== pred?] (schema-rules [:== parent-type]))
      (l/conda
       [(l/== value pred?)
        (l/== errors-out errors-in)]
       [(l/conso [:Error value :not pred?] errors-in errors-out)])]
      [(db-query [parent-type :=> pred?] (schema-rules [:=> parent-type]))
       (l/conda
        [(l/firsto value pred?)
         (l/== errors-out errors-in)]
        [(l/conso [:Error value :not pred?] errors-in errors-out)])]))))

(l/defne norm-coll-key [coll-type coll-key norm-key]
  ([:MAPP x x])
  ([:SEQQ x 0]))

#_(metrics/dice "asfdff" "asdf")

(defn named? [x]
  (or (string? x) (instance? clojure.lang.Named x)))

(defn key-like [thresh key other-key]
  (l/project [key]
             (l/project [other-key]
                        (if-not (and (named? key)
                                     (named? other-key))
                          l/fail
                          (let [score (metrics/dice (name key)
                                                    (name other-key))]
                            (l/== true
                                  (> score thresh))))
                        )))

(declare type-check pass-type-check?)

(defn path-for-type [schema-rules t result]
  (l/matche
   [t result]
   (['RootMap []])
   ([typ [k . rk]]
    (l/fresh [parent]
      (l/conde
       [(db-query [k :- [parent :> typ]] (:- schema-rules))
        (path-for-type schema-rules parent rk)]
       [(db-query [k :?- [parent :> typ]] (:?- schema-rules))
         (path-for-type schema-rules parent rk)])))))

(l/defne complex-config? [config-val]
  ([[:MAPP [k v] [k2 v2] . _]])
  ([[:SEQQ [k v] [k2 v2]. _]]))



(comment
  (l/run* [q]
    (complex-config? [:SEQQ [1 2]])
    )
  
  (l/run* [q]
    (path-for-type (spec 'RootMap
                         {:figwheel {string? integer?}
                          :cljsbuild {:forest integer?
                                      :trees 7}})
                   'RootMap:cljsbuild:forest
                   q))
  
)


(defn unknown-key-error [schema-rules parent-type typ bad-key config-val result]
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

(defn type-check [schema-rules config parent-type type-sig path err]
  (l/matche
   [type-sig path err config]
   ([[] [] errv [:MAPP]]
    (type-check-val schema-rules parent-type [:MAPP] [] err))
   ([[] [] errv [:SEQQ]]
    (type-check-val schema-rules parent-type [:SEQQ] [] err))
   ([[] [] errv [c]]
    (l/project [c]
               (l/== true (and (not= c :MAPP) (not= c :SEQQ))))
    (type-check-val schema-rules parent-type [c] [] err))
   ([[] [] errv c]
    (l/project [c]
               (l/== true (not (or (list? c) (vector? c) (seq? c)))))
    (type-check-val schema-rules parent-type c [] err))
   ([[typ . rt] [k . rk] errors [coll-type . _]]
    (l/fresh [pred? conf-val errv conf-val-type norm-key]
      (l/membero [k conf-val] config)
      (norm-coll-key coll-type k norm-key)
      (l/project
       [norm-key]
       (l/conda
        [(db-query [norm-key :- [parent-type :> typ]] (schema-rules [:- norm-key]))
         (l/conda
          [(type-check-val schema-rules parent-type config [] [])
           (type-check schema-rules conf-val typ rt rk errors)]
          [(type-check-val schema-rules parent-type config [] errors)])]       
        [(l/fresh [key-pred-key key-pred?]
           (db-query [key-pred-key :?- [parent-type :> typ]] (:?- schema-rules))
           (db-query [key-pred-key :k= key-pred?] (:k= schema-rules))
           (l/conda
            [(l/project [key-pred?] (l/pred k key-pred?))
             (type-check-val schema-rules parent-type config [] [])
             (type-check schema-rules conf-val typ rt rk errors)]
            [(l/project [key-pred?] (l/pred k key-pred?))
             (type-check-val schema-rules parent-type config errv errors)
             #_(type-check schema-rules conf-val typ rt rk errv)]           
            [(l/project [key-pred?]
                        (l/project [k]
                                   (l/== true (not (key-pred? k)))))
             (l/== rt [])
             (l/== rk [])
             (l/== errors [[:Error :key-doesnt-match-pred :k k :pred key-pred?]])]))]
        [(l/fresh [unknown-key error-res]
          ;; close out values
           (unknown-key-error schema-rules parent-type typ k conf-val error-res)
           (l/== rt [])
           (l/== rk [])
           (l/== errors [error-res])
           #_(l/== errors [[:Error :unknown-key :val k]])
           )])        )
      ))))

(type-check!!!
 (distinct
  (concat
   (spec 'RootMap
         {:figwheel (ref-schema 'FigOpt)})
   (spec 'RootMap
         {:figwheel string?})
   (spec 'FigOpt
         {:aaa 3
          :bbb 2})
   ))
 {:figwheel {:aaa 3}})

#_(type-check!!! (spec 'RootMap
                       {:figwheel {string? integer?}
                        :cljsbuild {:forest {:aaa 5 :bbb 6}
                                    :trees 7}})
                 {:asdf {:figwhel {"asdf" 5}}
                  :forst {:aaa 5 :bbb 6}
                  :cljsbuild {:forst 4}
                  })

(defn index-spec [spc]
  (merge
   (group-by second spc)
   (group-by (juxt second first) spc)))

(index-spec (spec 'ROOT
                  {:figwheel {:asdf 1
                              :bbb 3}
                   :cljsbuild {:asaa integer?}}))

;; really would be interesting to ignore confidence :high spelling errors

(defn pass-type-check? [schema-rules root-type config]
  (l/project [schema-rules]
             (l/project [root-type]
                        (l/project [config]
                                   (l/fresh [a b]
                                     (let [res (l/run* [q]
                                                 (type-check schema-rules
                                                             config
                                                             root-type
                                                             a 
                                                             b
                                                             q))]
                                       (if (empty? (flatten res)) l/succeed l/fail)))))))

#_(l/run* [q]
  (pass-type-check? (spec 'RootMap {:figwheel {:asdf 4}
                                    :cljsbuild {:fda 7}})
                    'RootMap:cljsbuild
                    (seqify {:asdf 4})))

;; it is hard to have or and return validation errors from a logic
;; system

;; this allows a for a simple "or" system where you just add more rules
;; and if one passes you have valid result

;; this is subtle but very interesting because it allows for the
;; simple extention of a set configuration rules

;; also, if it fails all, the failures are present in the result

(defn fix-or
  "Remove error results when we have a passing case for that key."
  [results]
  results
  #_(let [res (set (map (partial drop 2) results))]
    (filter (fn [x]
              (not (and
                    (res (concat [(nth x 2)] [[]]))
                    (not-empty (last x)))))
            results)))

(defn type-check!!! [grammer config]
  (walk/postwalk
   #(if (seq? %) (vec %) %)
   (fix-or
    (l/run* [q]
           (l/fresh [a t b err]
                    (type-check (index-spec grammer)
                                (seqify config)
                                'RootMap
                                a #_'(FigwheelOptions FigwheelOptions:server-port)
                                b
                                err)
                    (l/== q ['RootMap a b err]))))))

#_(type-check!!!
   (distinct
    (concat
     (spec 'RootMap {:figwheel 5})
     (spec 'RootMap {:figwheel 6})))
   {:figwheel 6})

#_(type-check!!! (spec 'RootMap {:figwheel 5}) {:figwheel 5})



#_(type-check!!! (spec 'RootMap {:figwheel {:stuff integer?
                                            :rash 4}}) {:figwheel {:asdf 4
                                                                   :robby 3}})

#_(type-check!!! (spec 'RootMap {:figwheel {:stuff integer?
                                            :rash 4}
                                 :cljsbuild {:thing integer?}})
                 {:figwheel {:stuff 5
                             :robby 3}
                  :cljsbuild {:thing "asdf"}})

(comment

(l/run* [q]
  (l/fresh [a t b err]
    (type-check (spec 'RootMap {:asdf map?})
                (seqify {:asdf {}})
                t #_'RootMap
                a #_'(FigwheelOptions FigwheelOptions:server-port)
                b
                #_(seqify {:server-port 5})
                
                err)
    (l/== q [t a b err])))

(l/run* [q]
  (l/fresh [a t b err]
    (type-check grammer
                (seqify {:figwheel {
                                    }
                         })
                t #_'RootMap
                a #_'(FigwheelOptions FigwheelOptions:server-port)
                b
                #_(seqify {:server-port 5})
                
                err)
    (l/== q [t a b err])))

  
  (l/run* [q]
          (membero [:figwheel q] (seqify {:figwheel {:server-port 5}})))

  (l/run* [q]
          (l/fresh [a t b err]
                   (type-check grammer
                               (seqify {:figwheel {:server-port 123}
                                        :cljsbuild {:repl-listen-port 123}
})
                               t #_'RootMap
                               a #_'(FigwheelOptions FigwheelOptions:server-port)
                               b
                               #_(seqify {:server-port 5})

                               err)
            (l/== q [t a b err])))

  )



;; Below here not in use

;; not in use
(comment

  (defn mapper
    "mapps a goal fn over two seqs"
    [f rule var]
    (l/conda
     [(l/emptyo rule)
      (l/== var rule)]
     [(l/fresh [x xs v vs]
               (l/conso x xs rule)
               (l/conso v vs var)
               (f x v)
               (mapper f xs vs))]))

  (l/defna paths [term result]
    ([[] []])
    ([[[k v] . r] s]
     (l/fresh [resr resv resk b to-map]
              (paths r resr)
              (l/matcha [v]
                        ([[[ff . _] . _]]
                         (paths v to-map)
                         (mapper (l/fne [x1 x2] ([[xxx ':- vvv] [[k . xxx] ':- vvv]])) to-map resv)
                         (l/appendo resv resr s))
                        ([v]
                         (l/appendo [[[k] ':- v]] resr s))))))

  (l/defna type-path [root-type path typed]
    ([_ [] []])
    ([rt [k . xs] [[k :- [rt :> o]] . res]]
     (membero [k :- [rt :> o]] grammer)
     (type-path o xs res)))

  (l/defna type-path2 [root-type path typed]
    ([_ [] []])
    ([rt [k] [rt o]]
     (membero [k :- [rt :> o]] grammer))
    ([rt [k . xs] [rt . res]]
     (l/fresh [o]
              (type-path2 o xs res)
              (membero [k :- [rt :> o]] grammer)))
    ([rt [k . xs] [k . res]] ;; error path
     (l/fresh [o]
              (type-path2 o xs res))))

  (l/run* [q]
          (l/fresh [pths firsts a b single-path path-type]
                   (paths (seqify {:figwheel {:server-port 5}
                    ; :cljsbuild {:repl-listen-port "asdf"}
                                   })pths)
                   (mapper l/firsto pths firsts)
                   (membero single-path firsts)
                   (type-path2 'RootMap single-path path-type)
                   (l/== q path-type)))

  (l/defna not-membero [in gg]
    ([i []])
    ([i [x . r]]
     (l/!= i x)
     (not-membero i r))))

