(ns figwheel-sidecar.type-check-test
  (:require
   [figwheel-sidecar.type-check :as tc :refer [spec type-check!!! seqify un-seqify ref-schema]]
   [clojure.walk :as walk]
   [clojure.core.logic :as l]
   [clojure.test :as t :refer [deftest is run-tests]]))


(defn test-grammer []
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

(defn every-uni? [x lis]
  (l/matche
   [lis]
   ([[]])
   ([[x . res]]
    (every-uni? x res))))

(defmacro is-matche [pattern body]
  `(is (not-empty
        (l/run* [q#]
          (l/matche [~body]
                    ([~pattern]))))))

;; an assertion that every list item matches pattern
(defmacro every-match [pattern body]
  `(and (not-empty ~body)
        (not-empty
         (l/run* [q#]
           (l/fresh [item#]
             (every-uni? item# ~body)
             (l/matche [item#] ([~pattern])))))))

(defmacro is-every [pattern body]
  `(is (every-match ~pattern ~body)))



(deftest my-test
  (let [gram (test-grammer)]
    (is-every [_ _ _ _] (type-check!!! (test-grammer) {:cljsbuild {}}))))

(deftest other-test
  (is-matche [['RootMap
               '[CljsBuildOptions CljsBuildOptions:repl-listen-port]
               [:cljsbuild :repl-listen-port]
               [[:Error "asdf" :not integer?]]]
              '[RootMap [FigwheelOptions FigwheelOptions:server-port] [:figwheel :server-port] []]]
             (type-check!!! (test-grammer) {:figwheel {:server-port 5}
                                            :cljsbuild {:repl-listen-port "asdf"}}))
  (is-matche 
   ['[RootMap [CljsBuildOptions CljsBuildOptions:repl-listen-port] [:cljsbuild :repl-listen-port] []]
    ['RootMap
     '[FigwheelOptions FigwheelOptions:server-port]
     [:figwheel :server-port]
     [[:Error "asdf" :not integer?]]]]
   (type-check!!! (test-grammer) {:figwheel {:server-port "asdf"}
                                  :cljsbuild {:repl-listen-port 5}}))
  (is-matche '[[RootMap [CljsBuildOptions] [:cljsbuild] []]]
             (type-check!!! (test-grammer) {:cljsbuild {}}))
  (is-matche '[[RootMap [FigwheelOptions] [:figwheel] []]]
             (type-check!!! (test-grammer) {:figwheel {}}))

             (type-check!!! (spec 'RootMap {:figwheel 5}) {:figwheel 5})
  
  )

(deftest vector-matching
  (let [gram (spec 'RootMap {:figwheel [{:asdf integer?}]})]

    #_(type-check!!! (spec 'RootMap []) {})

    (is-matche
     '[[RootMap [RootMap:figwheel RootMap:figwheel0 RootMap:figwheel0:asdf] [:figwheel 0 :asdf] []]]
     (type-check!!! gram {:figwheel [{:asdf 1}]}))
    (is-matche
     [['RootMap
       '[RootMap:figwheel RootMap:figwheel0 RootMap:figwheel0:asdf]
       [:figwheel 0 :asdf]
       [[:Error "asd" :not integer?]]]]
     (type-check!!! gram {:figwheel [{:asdf "asd"}]})))
  )



(deftest spec-test
  (is (spec 'Hey [integer?])
      ['[Hey := :SEQQ] ['Hey0 := integer?] [0 :- '[Hey :> Hey0]]])
  (is (spec 'Hey {:asdf integer?})
      [['Hey := :MAPP] ['Hey:asdf := integer?] [:asdf :- '[Hey :> Hey:asdf]]])
  (is (spec 'Hey []) '([Hey := :SEQQ]))
  (is (spec 'Hey []) '([Hey := :SEQQ])))

(deftest seqify-test
  (is (= 
       '(:MAPP [:figwheel (:SEQQ)] [:other (:MAPP [:fun (:MAPP [:stuff 5])])]
               [:other-thing (:MAPP)] [:cljsbuild (:SEQQ [0 (:MAPP [:server-ip "asdf"])])])
         (seqify
          {:figwheel []
           :other {:fun {:stuff 5}}
           :other-thing {}
           :cljsbuild '({:server-ip "asdf"})})
         ))
  (is (= (seqify {}) '(:MAPP)))
  (is (= (seqify []) '(:SEQQ)))
  (is (= (seqify {1 2}) [:MAPP [1 2]]))
  (is (= (seqify '(a b)) [:SEQQ [0 'a] [1 'b]]))
  (is (= (seqify '[a b]) [:SEQQ [0 'a] [1 'b]])))

(deftest de-sequify
  (is (= 
       (un-seqify
        '(:MAPP [:figwheel (:SEQQ)] [:other (:MAPP [:fun (:MAPP [:stuff 5])])]
                [:other-thing (:MAPP)] [:cljsbuild (:SEQQ [0 (:MAPP [:server-ip "asdf"])])]))
       {:figwheel []
         :other {:fun {:stuff 5}}
         :other-thing {}
         :cljsbuild '({:server-ip "asdf"})}
       ))
  (is (= {} (un-seqify '(:MAPP))))
  (is (= [] (un-seqify '(:SEQQ))))
  (is (= {1 2} (un-seqify [:MAPP [1 2]])))
  (is (= '(a b) (un-seqify [:SEQQ [0 'a] [1 'b]])))
  (is (= '[a b] (un-seqify [:SEQQ [0 'a] [1 'b]])))
  )
