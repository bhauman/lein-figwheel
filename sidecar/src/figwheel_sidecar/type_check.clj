(ns figwheel-sidecar.type-check
  (:require
   [clj-fuzzy.metrics :as metrics]
   [clojure.walk :as walk]
   [alandipert.intension :refer [make-db]]
   [datascript.core      :refer [q]]
   [clojure.core.logic :as l]))

(defn db-rel [db q]
  (fn [a]
    (l/to-stream
      (map #(l/unify a % q) db))))

(def schema {:blah {:borh 5}
             :root {:cljsbuild
                    {:other-crap string?
                     :builds integer?}}})

(def seqify (partial walk/postwalk #(cond
                                      (map-entry? %) %
                                      (map? %) (or (seq %) {})
                                      (vector? %) (map vector (range) %)
                                      (list? %) (map vector (range) %)
                                      :else %)))

(walk/postwalk-demo {:figwheel {:server-port 5}
                     :cljsbuild [{:server-ip "asdf"}]})

(seqify {:figwheel {}
         :cljsbuild '({:server-ip "asdf"})})

(defn decompose [type-gen-fn node' s']
  (letfn [(map?? [x] (and (seq? x)
                          (every? map-entry? x)))
          (list?? [x] (and (seq x)
                           (every? #(and (= (count %) 2)
                                    (integer? (first %)))
                              s')))
          (pred [x] (cond
                      (map?? x) map??
                      (list?? x) list??))
          (decomp [node s]
            (if (nil? s)
              nil
              (let [[[k v] & r] s
                    t (type-gen-fn node k)]
                (if (seq? v)
                  (concat [[t := (pred v)]
                           [k :- [node :> t]]]
                          (decomp t v)
                          (decomp node r))
                  (concat (if (fn? v)
                            [[t := v]
                             [k :- [node :> t]]]
                            [[k :- [node :> v]]])
                          (decomp node r))))))]
    (cons
     [node' := (pred s')]
     (decomp node' s'))))

(def parse-rules    (comp (partial decompose #(gensym 'type) :RootMap) seqify))
(def analyze-config (comp (partial decompose (fn [_] (l/lvar)) :RootMap) seqify))

(defn spec [type body]
  (decompose (fn [node k] (symbol (str (name node) k))) type (seqify body)))

   (spec 'RootMap
         {:cljsbuild 'CljsBuildOptions
          :welwel [integer?]
          :figwheel  'FigwheelOptions})

(defn analyze [type body]
  (decompose (fn [_ _] (l/lvar)) type (seqify body)))

(def grammer
  (concat
   (spec 'RootMap
         {:cljsbuild 'CljsBuildOptions
          :figwheel  'FigwheelOptions})
   (spec 'CljsBuildOptions
         {:repl-listen-port integer?
          :crossovers       integer?})
   (spec 'FigwheelOptions
         {:server-port integer?
          :server-ip   string?})))

(analyze 'RootMap
         {:figwheel {:server-port 5}
          :cljsbuild {:server-ip "asdf"}})

;; type inference
; type infer a tree
;; type check a tree

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

(l/run* [q]
  (mapper (fn [a b]
            (l/conde
             [(l/== a 3)
              (l/== b a)]
             [(l/== a 2)
              (l/== b 20)]
             [(l/== a 1)
              (l/== b 10)])) [1 2 3]  q))

(l/defna paths [term result]
  ([[] []])
  ([[[k v] . r] s]
   (l/fresh [resr resv resk b to-map]
     (paths r resr)
     (l/matcha [v]
               ([ [[ff . _] . _] ]
                (paths v to-map)
                (mapper (l/fne [x1 x2] ([[xxx ':- vvv] [[k . xxx] ':- vvv]])) to-map resv)
                (l/appendo resv resr s))
               ([v]
                (l/appendo [[ [k] ':- v]] resr s))))))

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

(l/defna listo [arg]
  ([[]])
  ([[x . _]]))

;; mising key error
;; vector types

(l/defne type-check [parent' type-sig path config err]
  #_([_ [] [] [] []] l/fail)
  ([_ [] [] c []]
   ;; this gets rid of partial matches
   (l/conda 
    [(listo c) l/fail]
    [l/succeed]))
  ([parent [typ . rt] [k . rk] conf errors]
   (l/fresh [pred? body errv]
     (l/membero [k body] conf)
     (l/membero [k :- [parent :> typ]] grammer)

     (type-check typ rt rk body errv)
     (l/membero [typ := pred?] grammer)
     (l/conda
      [(l/project [pred?]
                  (l/pred body pred?))
       (l/== errv errors)]
      [(l/conso [:Error body :not pred?]
                errv errors)]))))

(l/run* [q]
  (l/membero [:figwheel q] (seqify {:figwheel {:server-port 5}})))

(l/run* [q]
  (l/fresh [a t b err]
    (type-check t #_'RootMap
                a #_'(FigwheelOptions FigwheelOptions:server-port)
                b
                #_(seqify {:server-port 5})
                (seqify {:figwheel {:server-port 5}
                         :cljsbuild {:repl-listen-port "asdf"}
                         })
                err)
    (l/== q [t a b err])))

grammer

(l/run* [q]
  (l/fresh [pths firsts a b single-path path-type]
    (paths (seqify {:figwheel {:server-port 5}
                    ; :cljsbuild {:repl-listen-port "asdf"}
                    }) pths)
    (mapper l/firsto pths firsts)
    (l/membero single-path firsts)
    (type-path2 'RootMap single-path path-type)
    (l/== q path-type)
    ))



(l/defne lvar-em [xs out]
  ([[] []])
  ([[[x] . y] [[z] . o]]
   (lvar-em y o)
   (l/pred x fn?)
   (l/project [x]
              (l/pred z x)))
  ([[[x] . y] [[x] . o]]
   (lvar-em y o)))


(l/run* [q]
  (l/fresh [a b]
    (l/== q [[1] ["asdf"]])
    (lvar-em [[integer?] [string?]] q)

    ))


(let [rules   grammer 
      #_configs #_(analyze 'RootMap
                       {:figwheel {:server-port 5
                                   :server-port2 2}
                        :cljsbuild {:server-ip "asdf"}})]
  (l/run* [q]
    (l/fresh [parent child configs]
      (l/== configs (analyze 'RootMap
                       {:figwheel {:server-port 5
                                   :server-port2 2}
                        :cljsbuild {:server-ip "asdf"}}))
      (l/membero parent configs)
      (l/membero child configs)
      (l/matche
       [parent child]
       ([[_ :- [_ :> a]] [_ :- [a :> _]]]
        
        (l/project [a]
          (l/project [b]
                     (l/== (= a b) true)))))
      #_(l/membero parent rules)
      #_(l/membero child rules)
      
      
      (l/== q [parent child])
      )))




(parse-rules {:blah {:borh 5}
              :root {:cljsbuild 5}})

(parse-rules {:blah {:borh 5}
              :root {:cljsbuild 5}})




;; 

(analyze-config {:blah {:borh 5}
                 :root {:cljsbuild 5}})

(def ^:dynamic *rules* nil)
(def ^:dynamic *configs* nil)

(l/defna not-membero [in gg]
  ([i []])
  ([i [x . r]]
   (l/!= i x)
   (not-membero i r)))

;; types that haven't matched finding
(l/defna find-type-rules [rules rule out]
  ([[] _ []])
  ;; find other keys with this type ?? that are spelled similarly
  ([[[k :- pt] . rs] [_ :- pt] [[k :- pt] . res]]
   (find-type-rules rs rule res))
  ([[x . rs] _ o]
   (find-type-rules rs rule o)))

(let [rules  (parse-rules {:blah {:borh 5}
                           :root {:cljsbuild 5
                                  :other-key 6
                                  :other-keyer 6}})
      configs (parse-rules {:blah {:borh 5}
                            :root {:cljsbuild 6}})]
  (binding [*rules* rules]
    (l/run* [q]
      (l/fresh [res-set out ll tt]
        (l/membero res-set configs)
        (not-membero res-set rules)
        (find-type-rules rules res-set out)
        (l/== q out)))))



(l/defne parent-child [a b]
  ([[_ ':> _ ':> c] [c ':> _ ':> _]])
  ([[_ ':> _ ':> c] [c ':> _]]))

(defn ancestor [x y]
  (l/conde
   [(parent-child x y)]
   [(l/fresh [res]
      (parent-child x res)
      (ancestor res y))]))

(defn valid-child [rp cp pc out]
  ([[] []])

  )


(defn rooted-parent [parent child]
  (l/fresh [a b]
    (ancestor ['RootMap ':> a ':> b] parent)
    (parent-child parent child)))


(let [rules  (parse-rules {:blah {:borh 5}
                           :root {:cljsbuild 5}})
      configs (parse-rules {:blah {:borh 5}
                            :root {:cljsbuild 5
                                   :forest {:fire 3}}})]
  (binding [*rules* rules]
    (l/run* [q]
      (l/fresh [p c child]
         (l/onceo
          (l/all
   ))
        (db-rel configs p)

        (rooted-parent p (last configs))
        (db-rel configs child)
        (parent-child p child)
         (l/== q child)
         #_(l/== q [a :- b :- c])
         #_(l/lvaro a))
)))


(let [rules  (parse-rules {:blah {:borh 5}
                           :root {:cljsbuild 5}})
      configs (last (analyze-config {:blah {:borh 5}
                               :root {:cljsbuild 5}}))]
  (binding [*rules* rules]
    (l/run* [q]
      (l/fresh [rule config a b c]
        (l/membero q configs)
        (not-membero q rules)
        #_(l/== q [a :- b :- c])
        #_(l/lvaro a)))))


(l/defne parent-child [p ch]
  ([[_ ':> _ ':> b] [b ':> _ ':> _]]))

(defn config-parents [ch parents]
  (db-rel *configs* ch)
  (parent-child parents ch))

(defn config-children [parent children]
  (db-rel *configs* children)
  (parent-child parent children))

(defn valid-tree [node configs rules]
  ()

  )


(l/defna match-rule [rule config out]
  ([x x x])
  ([[a ':> k ':> c] [a ':> k ':> d] [:Error k c :not d]])
  ([[a ':> k ':> c] _ _ ]

   );; leaf not =
)

(let [rules  (parse-rules {:blah {:borh 5}
                           :root {:cljsbuild 5}})
      configs (analyze-config {:blah {:borh 5}
                               :root {:cljsbuild 5}})]
  (binding [*rules* rules]
    (l/run* [q]
      (l/fresh [rule config out]
        (db-rel rules rule)
        (db-rel configs config)
        (l/== rule config)
        #_(match-rule rule config out)
        (l/== q config)))
    ))




(l/defna decomp [term result]
  ([[] []])
  ([[[k v] . r] s]
   (l/fresh [resv resr resk f ff z]
     (l/conda
      [(l/firsto v f)
       (decomp v resv)
       (decomp r resr)
       (l/firsto f ff)
       (l/appendo [[k ':-- ff]] resv resk)
       (l/appendo resk resr s)]
      [(decomp r resr)
       (l/appendo [[k ':- v]] resr s)]))))

(l/defna decomp [a term result]
  ([_ [] []])
  ([_ [[k v] . r] s]
   (l/fresh [resr resv resk b]
     (decomp a r resr)
     (l/matcha [v]
               ([ [[ff . _] . _] ]
                (decomp b v resv)
                (l/appendo [[a ':-- k ':-- b]] resv resk)
                (l/appendo resk resr s))
               ([v]
                (l/appendo [[a ':-- k ':- v]] resr s))))))

(l/defna findo [x l o]
  ([x' [x' . r ] x'])
  ([x' [i' . r ] _]
   (findo x' r o)))

(defn validate []
  
  )



(l/run* [q]
  (findo 1 [2 1 4] q))


(l/run* [q]
  (l/appendo [:a] [:b :c] q))

(l/run* [q]
  (l/fresh [a]
    (decomp a (seqify schema)
            q)
    )

  )

(def rules (list
            [:a :-- :blah :-- :b]
            [:b :-- :borh :-- 5]
            [:a :-- :root :-- :c]
            [:c :-- :cljsbuild :-- :d]
            [:d :-- :other-crap :-- :string?]
            [:d :-- :builds :-- :integer?]))



(defn parent-key [rules keya keyb]
  (l/fresh [a b c dba dbb]
    (db-rel rules [a :-- keya :-- b])
    (db-rel rules [b :-- keyb :-- c])))

(defn parent-key [ka kb]
  (l/fresh [a b d r]
    (db-rel rules [a :-- ka :-- b])
    (db-rel rules [b :-- kb :-- d])))

(l/run* [q]
  (l/fresh [a b]
    (parent-key a b)
    (l/== [a b] q)
    ))

(defn valid-path [ka kb out]
  (l/conde
   [(l/fresh [x res]
      (parent-key ka x)
      (l/conso ka res out)
      (valid-path x kb res))]
   [(parent-key ka kb)
    (l/== [ka kb] out)]
   
   #_[(parent-key ka kb)
      #_(l/lvaro ka)
      (l/== [[:error ka]] out)]
   #_[(parent-key ka kb)
      (l/lvaro kb)
      (l/== [[:error kb]] out)]

   ))


(l/run* [q]
  (l/fresh [a b]
    (valid-path :root :builds q  )))


;;; this is the most promising
(defn match-child [vp pc child]
  (l/conda
   [(parent-key vp pc)
    (l/== child pc)]
   [(l/== child [:error pc])]))

(l/defne valid-pather [root path out]
  ([_ [] []])
  ([_ [x . xs] o]
   (l/fresh [check-res y rrr]
     (match-child root x check-res)
     (l/conda
      [(l/== [:error y] check-res) (l/== o [check-res])]
      [(l/conso x rrr o)
       (valid-pather x xs rrr)]))))

(l/run* [q]
  (l/fresh [a]
      (valid-pather :root [a :other-craper] q))
  )


;;; not lets make our specification 









(l/defna pcomp [p1 p2 out]
  ([[] [] []])
  ([[] _ []])
  ([_ [] []])
  ([[x] [y] [x]]
   (l/== x y))
  ([[x] [y] [[:error y]]])
  ([[x x1 . xs] [y y1 . ys] [z . os]]
   (l/conda
    [(l/== x y)
     (l/== z x)
     (parent-key x x1)
     (parent-key y y1)
     (l/fresh [r r2]
       (l/conso x1 xs r)
       (l/conso y1 ys r2)
       (pcomp r r2 os))]
    [(l/!= x y)
     (l/== z [[:error x]])
     (l/== os [])]
    )))

(l/run* [q]
  (l/fresh [a b]
    (pcomp [:root :cljsbuild :builds]
           [:root :cljsbuild :builds] q)))






(defn path [n n2]
  

  )




#_(schema->db schema)

(def ddd
  {:cljsbuild
   {:other-crap 5
    :builds
    {:dev {:source-paths ["yep" "there" 3]
           :compiler
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
            :optimizations :noner}}}}})





(defn mapper
  "mapps a goal fn over two seqs"
  [f rule var]
  (l/conde
   [(l/emptyo rule)
    (l/== var rule)]
   [(l/fresh [x xs v vs]
      (l/conso x xs rule)
      (l/conso v vs var)
      (f x v)
      (mapper f xs vs))]))

(defn rule-match [x v]
  (l/conde
   [(l/pred x fn?)
    (l/project [x]
               (l/pred v x))]
   [(l/== x v)]))

(def crule (partial mapper rule-match))

(defn db-rel [db q]
  (fn [a]
    (l/to-stream
      (map #(l/unify a % q) db))))

(defn matching-paths* [db q]
  (l/fresh [db-rule rule-path rule-pred
            db-config config-path config-val]
    (db-rel db db-rule)
    (db-rel db db-config)      
    (l/== [rule-path :schema rule-pred] db-rule)
    (l/== [config-path :config config-val] db-config)
    (crule rule-path config-path)
    (l/== q [config-path config-val])))

(defn matching-paths [db]
  (l/run* [q] (matching-paths* db q)))

(matching-paths (total-db))

(let [db (total-db)
      matched-config-paths (map first (matching-paths (total-db)))]
  (l/run* [q]
    (l/fresh [db-config config-path config-val mconfig-paths
              cf cr
              mf mr]
      (db-rel db db-config)
      (db-rel matched-config-paths mconfig-paths)
      (l/== [config-path :config config-val] db-config)
      (l/conso cf cr config-path)
      (l/conso mf mr mconfig-paths)
      (l/== cr mr)
      (l/!= mf cf)
      (l/== q config-path))))

