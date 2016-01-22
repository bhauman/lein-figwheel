(ns figwheel-sidecar.type-check
  (:require
   [clj-fuzzy.metrics :as metrics]
   [clojure.walk :as walk]
   [alandipert.intension :refer [make-db]]
   [datascript.core      :refer [q]]
   [clojure.core.logic :as l]
   [clojure.test :as t :refer [deftest is run-tests]]))

(def ^:dynamic *schema-rules* [])

(defn db-rel [db q]
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

(defn decompose [type-gen-fn node' s']
  (letfn [(decomp [node s]
            (if (nil? s)
              nil
              (let [[[k v] & r] s
                    t (type-gen-fn node k)]
                (if (or (list?? v) (map?? v))
                  (concat [[t :=> (first v)]
                           [k :- [node :> t]]]
                          (decomp t (rest v))
                          (decomp node r))
                  (concat (cond (fn? v) 
                                [[t := v]
                                 [k :- [node :> t]]]
                                (-> v meta :ref)
                                [[k :- [node :> v]]]
                                :else ;this is a value comparison
                                [[t :== v]
                                 [k :- [node :> t]]])
                          (decomp node r))))))]
    (cond
      (or (list?? s') (map?? s'))
      (cons
       [node' :=> (first s')]
       (if (empty?? s') [] (decomp node' (rest s'))))
      (fn? 's)
      [[node' := s']]
      :else
      [[node' :== s']]
      )))

(defn ref-schema [s]
  (with-meta s {:ref true}))

(spec 'Hey {:asdf {:hey 5
                   :something (ref-schema 'BadAss)}})

(defn spec [type body]
  (#'decompose (fn [node k] (symbol (str (name node) k))) type (seqify body)))


(comment
  (spec 'Hey {integer? :hey})
  
  
  
  )

(def parse-rules    (comp (partial decompose #(gensym 'type) :RootMap) seqify))
(def analyze-config (comp (partial decompose (fn [_] (l/lvar)) :RootMap) seqify))



(comment
  (spec 'RootMap
        {:cljsbuild 'CljsBuildOptions
         :welwel [integer?]
         :figwheel  'FigwheelOptions}))

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
  (l/fresh [pred?]
    (l/conde
     [(l/membero [parent-type := pred?] schema-rules)
      (l/conda
       [(l/project [pred?] (l/pred value pred?))
        (l/== errors-out errors-in)]
       [(l/conso [:Error value :not pred?] errors-in errors-out)])]
     [(l/membero [parent-type :== pred?] schema-rules)
      (l/conda
       [(l/== value pred?)
        (l/== errors-out errors-in)]
       [(l/conso [:Error value :not pred?] errors-in errors-out)])]
     [(l/membero [parent-type :=> pred?] schema-rules)
      (l/conda
       [(l/firsto value pred?)
        (l/== errors-out errors-in)]
       [(l/conso [:Error value :not pred?] errors-in errors-out)])])))

(l/defne norm-coll-key [coll-type coll-key norm-key]
  ([:MAPP x x])
  ([:SEQQ x 0]))

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
      (l/membero [norm-key :- [parent-type :> typ]] schema-rules)
      (type-check schema-rules conf-val typ rt rk errv)
      (type-check-val schema-rules parent-type config errv errors)))))

(type-check!!! (spec 'RootMap {:figwheel 5}) {:figwheel 5})

(type-check!!! (spec 'RootMap {:figwheel {:stuff integer?}}) {:figwheel {}})



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
  (l/fresh [a b]
    (l/membero [a b] [:mapp [1 2] [3 4]])
    (l/== q [a b])))

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

(comment

  (l/run* [q]
          (l/membero [:figwheel q] (seqify {:figwheel {:server-port 5}})))

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

(defn type-check!!! [grammer config]
  (walk/postwalk
   #(if (seq? %) (vec %) %)
   (l/run* [q]
           (l/fresh [a t b err]
                    (type-check grammer
                                (seqify config)
                                t #_'RootMap
                                a #_'(FigwheelOptions FigwheelOptions:server-port)
                                b
                                err)
                    (l/== q [t a b err])))))

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
     (l/membero [k :- [rt :> o]] grammer)
     (type-path o xs res)))

  (l/defna type-path2 [root-type path typed]
    ([_ [] []])
    ([rt [k] [rt o]]
     (l/membero [k :- [rt :> o]] grammer))
    ([rt [k . xs] [rt . res]]
     (l/fresh [o]
              (type-path2 o xs res)
              (l/membero [k :- [rt :> o]] grammer)))
    ([rt [k . xs] [k . res]] ;; error path
     (l/fresh [o]
              (type-path2 o xs res))))

  (l/run* [q]
          (l/fresh [pths firsts a b single-path path-type]
                   (paths (seqify {:figwheel {:server-port 5}
                    ; :cljsbuild {:repl-listen-port "asdf"}
                                   })pths)
                   (mapper l/firsto pths firsts)
                   (l/membero single-path firsts)
                   (type-path2 'RootMap single-path path-type)
                   (l/== q path-type)))

  (l/defna not-membero [in gg]
    ([i []])
    ([i [x . r]]
     (l/!= i x)
     (not-membero i r))))
