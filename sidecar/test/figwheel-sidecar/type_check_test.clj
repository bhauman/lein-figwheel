(ns figwheel-sidecar.type-check-test
  (:require
   [figwheel-sidecar.type-check :as tc :refer [with-schema index-spec spec type-checker type-check!!! seqify un-seqify ref-schema pass-type-check?]]
   [clojure.walk :as walk]
   [clojure.core.logic :as l]
   [clojure.test :as t :refer [deftest is run-tests]]))

(defn test-grammer []
  (index-spec
   (spec 'RootMap
         {:cljsbuild (ref-schema 'CljsBuildOptions)
          :figwheel  (ref-schema 'FigwheelOptions)
          :static    :huh?})
   (spec 'CljsBuildOptions
         {:repl-listen-port integer?
          :crossovers       integer?})
   (spec 'FigwheelOptions
         {:server-port integer?
          :server-ip   string?
          :source-paths [string?]})))

(deftest basic-passing
  (with-schema (test-grammer)
    (is (empty? (type-checker 'RootMap {} {})))
    (is (empty? (type-checker 'RootMap {:figwheel {}} {})))
    (is (empty? (type-checker 'RootMap {:cljsbuild {}} {})))
    (is (empty? (type-checker 'RootMap {:static :huh?} {})))    
    (is (empty? (type-checker 'RootMap {:figwheel {:server-port 5}} {})))
    (is (empty? (type-checker 'RootMap {:figwheel {:server-ip "asdf"}} {})))
    (is (empty? (type-checker 'RootMap {:figwheel {:source-paths []}} {})))
    (is (empty? (type-checker 'RootMap {:figwheel {:source-paths ["asdf" "asdf" "asdf"]}} {})))
    (is (empty? (type-checker 'RootMap {:cljsbuild {:repl-listen-port 5}} {})))
    (is (empty? (type-checker 'RootMap {:cljsbuild {:crossovers 5}} {})))
    (is (empty? (type-checker 'RootMap {:figwheel {:server-port 5
                                                   :server-ip "asdf"
                                                   :source-paths ["asdf" "asdf" "asdf"]}
                                        :cljsbuild {:repl-listen-port 5
                                                    :crossovers 5}
                                        :static :huh?} {})))))

(deftest basic-errors
  (with-schema (test-grammer)
    (is (= (type-checker 'RootMap 5 {})
           [{:Error-type :failed-predicate, :not :MAPP, :value 5, :type-sig '(RootMap), :path nil}]))
    (is (= (type-checker 'RootMap [] {})
           '[{:Error-type :failed-predicate, :not :MAPP, :value [], :type-sig (RootMap), :path nil}]))
    (is (= (type-checker 'RootMap {:figwheeler {}} {})
           '({:Error-type :unknown-key, :key :figwheeler, :value {}, :type-sig (RootMap), :path (:figwheeler)})))
    (is (= (type-checker 'RootMap {:cljsbuilder {}} {})
           '({:Error-type :unknown-key, :key :cljsbuilder, :value {}, :type-sig (RootMap), :path (:cljsbuilder)})))
    (is (= (type-checker 'RootMap {:figwheel {:server-porter 5}} {})
           '({:Error-type :unknown-key,
              :key :server-porter,
              :value 5,
              :type-sig (FigwheelOptions RootMap),
              :path (:server-porter :figwheel)})))
    (is (= (type-checker 'RootMap {:figwheel {:server-port "asdf"}} {})
           [{:Error-type :failed-predicate,
             :not clojure.core/integer?,
             :value "asdf",
             :type-sig '(FigwheelOptions:server-port FigwheelOptions RootMap),
             :path '(:server-port :figwheel)}]))
    (is (= (type-checker 'RootMap {:figwheel {:source-paths ["asdf" 4 "asdf"]}} {})
           [{:Error-type :failed-predicate,
             :not clojure.core/string?,
             :value 4,
             :type-sig '(FigwheelOptions:source-paths0 FigwheelOptions:source-paths FigwheelOptions RootMap),
             :path '(1 :source-paths :figwheel)}]))))

(defn boolean? [x] (or (true? x) (false? x)))

(deftest base-cases
  (with-schema (index-spec
                (spec 'String string?)
                (spec 'Integer integer?)
                (spec 'Five 5)
                (spec 'Map {})
                (spec 'AnotherInt (ref-schema 'Integer))
                (spec 'IntOrBool boolean?)
                (spec 'IntOrBool (ref-schema 'AnotherInt)))
    (is (empty? (type-checker 'String "asdf" {})))
    (is (empty? (type-checker 'Integer 6 {})))
    (is (empty? (type-checker 'Five 5 {})))
    (is (empty? (type-checker 'Map {} {})))
    (is (empty? (type-checker 'AnotherInt 15 {})))
    (is (empty? (type-checker 'IntOrBool true {})))
    (is (empty? (type-checker 'IntOrBool 15 {})))

    (is (= (type-checker 'String :blah {})
           [{:Error-type :failed-predicate,
             :not clojure.core/string?,
             :value :blah,
             :type-sig '(String),
             :path nil}]))
    (is (= (type-checker 'Integer :blah {})
           [{:Error-type :failed-predicate,
             :not clojure.core/integer?,
             :value :blah,
             :type-sig '(Integer),
             :path nil}]))
    (is (= (type-checker 'Five :blah {})
           [{:Error-type :failed-predicate,
             :not 5,
             :value :blah,
             :type-sig '(Five),
             :path nil}]))
    (is (= (type-checker 'AnotherInt :blah {})
           [{:Error-type :failed-predicate,
             :not clojure.core/integer?,
             :value :blah,
             :type-sig '(AnotherInt),
             :path nil}]))
    ;; consolidate this into a single error?
    (is (= (type-checker 'IntOrBool :blah {})
           [{:Error-type :failed-predicate,
             :not boolean?
             :value :blah,
             :type-sig '(IntOrBool),
             :path nil}
            {:Error-type :failed-predicate,
             :not integer?
             :value :blah,
             :type-sig '(IntOrBool),
             :path nil}]))
    
    )


  )


(comment

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
          :server-ip   string?})
   ))

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
  (is-matche ['[RootMap [FigwheelOptions FigwheelOptions:server-port] [:figwheel :server-port] []]
              ['RootMap
               '[CljsBuildOptions CljsBuildOptions:repl-listen-port]
               [:cljsbuild :repl-listen-port]
               [[:Error "asdf" :not integer?]]]
              ]
             (type-check!!! (test-grammer) {:figwheel {:server-port 5}
                                            :cljsbuild {:repl-listen-port "asdf"}}))
  (is-matche 
   [['RootMap
     '[FigwheelOptions FigwheelOptions:server-port]
     [:figwheel :server-port]
     [[:Error "asdf" :not integer?]]]
    '[RootMap [CljsBuildOptions CljsBuildOptions:repl-listen-port] [:cljsbuild :repl-listen-port] []]]
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
)
