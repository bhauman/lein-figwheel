(ns figwheel-sidecar.type-check-test
  (:require
   [figwheel-sidecar.type-check :as tc :refer [spec type-check!!! seqify un-seqify ref-schema pass-type-check?]]
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

(deftest predicate-keys
  (is-matche
   [['RootMap
     '[RootMap:figwheel RootMap:figwheel:pred-key_1549686445]
     [:figwheel 6]
     [[:Error :key-doesnt-match-pred :k 6 :pred _]]]]
   (type-check!!!
    (spec 'RootMap
          {:figwheel {string? integer?}})
    {:figwheel {6 5}}))
  (is-matche
   '[[RootMap [RootMap:figwheel RootMap:figwheel:pred-key_1549686445] [:figwheel "asdf"] []]]
   (type-check!!!
    (spec 'RootMap
          {:figwheel {string? integer?}})
    {:figwheel {"asdf" 5}})))


(deftest smart-key-errors
  (is-matche
   '[[RootMap
      [CljsBuildOptions CljsBuildOptions:repl-listen-port]
      [:cljsbuild :repl-listn-port]
      [[:Error :mispelled-key :key :repl-listn-port :correction :repl-listen-port :confidence :high]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuild {:repl-listn-port 1234}}))
  (is-matche
   '[[RootMap
      [CljsBuildOptions CljsBuildOptions:repl-listen-port]
      [:cljsbuild :repl-lisn-prt]
      [[:Error :mispelled-key :key :repl-lisn-prt :correction :repl-listen-port :confidence :high]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuild {:repl-lisn-prt 1234}}))
  (is-matche
   '[[RootMap
      [FigwheelOptions CljsBuildOptions:repl-listen-port]
      [:figwheel :repl-listen-port]
      [[:Error
        :misplaced-key
        :key
        :repl-listen-port
        :correct-type
        [CljsBuildOptions :> CljsBuildOptions:repl-listen-port]
        :correct-path
        [:repl-listen-port :cljsbuild]
        :confidence
        :high]]]]
   (type-check!!!
    (test-grammer)
    {:figwheel {:repl-listen-port 1234}}))

  (is-matche
   '[[RootMap
      [CljsBuildOptions FigwheelOptions:server-port]
      [:cljsbuild :server-port]
      [[:Error
        :misplaced-key
        :key
        :server-port
        :correct-type
        [FigwheelOptions :> FigwheelOptions:server-port]
        :correct-path
        [:server-port :figwheel]
        :confidence
        :high]]]
     [RootMap
      [CljsBuildOptions FigwheelOptions:server-ip]
      [:cljsbuild :server-ip]
      [[:Error
        :misplaced-key
        :key
        :server-ip
        :correct-type
        [FigwheelOptions :> FigwheelOptions:server-ip]
        :correct-path
        [:server-ip :figwheel]
        :confidence
        :high]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuild
     {:server-port 1234
      :server-ip   "asdf"}}))


  (is-matche
   '[[RootMap [FigwheelOptions] [:cljsbuilderer] [[:Error :wrong-key-used :key :cljsbuilderer :correct-key :figwheel :confidence :high]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuilderer
     {:server-port 1234
      :server-ip   "asdf"}}))

  (is-matche
   '[[RootMap
      [CljsBuildOptions FigwheelOptions:server-port]
      [:cljsbuild :server-port]
      [[:Error
        :misplaced-key
        :key
        :server-port
        :correct-type
        [FigwheelOptions :> FigwheelOptions:server-port]
        :correct-path
        [:server-port :figwheel]
        :confidence
        :high]]]
     [RootMap
      [CljsBuildOptions FigwheelOptions:server-ip]
      [:cljsbuild :server-ip]
      [[:Error
        :misplaced-key
        :key
        :server-ip
        :correct-type
        [FigwheelOptions :> FigwheelOptions:server-ip]
        :correct-path
        [:server-ip :figwheel]
        :confidence
        :high]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuild
     {:server-port 1234
      :server-ip   "asdf"}}))

    (is-matche
     '[[RootMap
        [CljsBuildOptions FigwheelOptions:server-port]
        [:cljsbuild :server-porter]
        [[:Error
          :mispelled-and-misplaced-key
          :key
          :server-porter
          :correct-type
          [FigwheelOptions :> FigwheelOptions:server-port]
          :correct-path
          [:server-port :figwheel]
          :confidence
          :high]]]
       [RootMap
        [CljsBuildOptions FigwheelOptions:server-ip]
        [:cljsbuild :server-iper]
        [[:Error
          :mispelled-and-misplaced-key
          :key
          :server-iper
          :correct-type
          [FigwheelOptions :> FigwheelOptions:server-ip]
          :correct-path
          [:server-ip :figwheel]
          :confidence
          :high]]]]
     (type-check!!!
      (test-grammer)
      {:cljsbuild
       {:server-porter 1234
        :server-iper   "asdf"}}))
  
  (is-matche
   '[[RootMap [FigwheelOptions] [:figwheeler] [[:Error :mispelled-key :key :figwheeler :correction :figwheel :confidence :low]]]]
   (type-check!!!
    (test-grammer)
    {:figwheeler
     {:what 1234
      :water   "asdf"}}))

  (is-matche
   '[[RootMap
      [CljsBuildOptions FigwheelOptions:server-port]
      [:cljsbuild :server-port]
      [[:Error
        :misplaced-key
        :key
        :server-port
        :correct-type
        [FigwheelOptions :> FigwheelOptions:server-port]
        :correct-path
        [:server-port :figwheel]
        :confidence
        :low]]]]
   (type-check!!!
    (test-grammer)
    {:cljsbuild
     {:server-port []}}))

    (is-matche
     '[[RootMap
        [CljsBuildOptions FigwheelOptions:server-port]
        [:cljsbuild :server-rt]
        [[:Error
          :mispelled-and-misplaced-key
          :key
          :server-rt
          :correct-type
          [FigwheelOptions :> FigwheelOptions:server-port]
          :correct-path
          [:server-port :figwheel]
          :confidence
          :low]]]
       [RootMap
        [CljsBuildOptions FigwheelOptions:server-ip]
        [:cljsbuild :server-rt]
        [[:Error
          :mispelled-and-misplaced-key
          :key
          :server-rt
          :correct-type
          [FigwheelOptions :> FigwheelOptions:server-ip]
          :correct-path
          [:server-ip :figwheel]
          :confidence
          :low]]]]
     (type-check!!!
      (test-grammer)
      {:cljsbuild
       {:server-rt []}}))
  

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
