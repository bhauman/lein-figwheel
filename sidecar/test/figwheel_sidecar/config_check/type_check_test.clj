(ns figwheel-sidecar.config-check.type-check-test
  (:require
   [figwheel-sidecar.type-check :as tc :refer [parents-for-type get-paths-for-type
                                               requires-keys
                                               with-schema index-spec or-spec spec type-checker seqify ref-schema]]
   [clojure.walk :as walk]
   [clojure.test :as t :refer [deftest is testing run-tests]]))

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
                (spec 'Cljsbuild {:server-port integer?
                                  :server-ip string?})
                (or-spec 'IntOrBool
                      boolean?
                      (ref-schema 'AnotherInt))
                (or-spec 'IntOrBoolOrCljs
                      (ref-schema 'IntOrBool)
                      (ref-schema 'Cljsbuild))
                )
    (is (empty? (type-checker 'String "asdf" {})))
    (is (empty? (type-checker 'Integer 6 {})))
    (is (empty? (type-checker 'Five 5 {})))
    (is (empty? (type-checker 'Map {} {})))
    (is (empty? (type-checker 'AnotherInt 15 {})))
    (is (empty? (type-checker 'IntOrBool true {})))
    (is (empty? (type-checker 'IntOrBool 15 {})))

    (is (empty? (type-checker 'IntOrBoolOrCljs 15 {})))
    (is (empty? (type-checker 'IntOrBoolOrCljs true {})))

    (is (empty? (type-checker 'IntOrBoolOrCljs {} {})))
    (is (empty? (type-checker 'IntOrBoolOrCljs {:server-port 12
                                                :server-ip "asdf"} {})))
    #_(is (empty? (type-checker 'IntOrBoolOrCljs {:server-port 12
                                                :server-ip "asdf"} {})))

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
             :path nil
             :sub-type 'Integer}]))
    ;; consolidate this into a single error?
    (is (= (type-checker 'IntOrBool :blah {})
           [{:Error-type :failed-predicate,
             :not boolean?
             :value :blah,
             :type-sig '(IntOrBool),
             :path nil
             :sub-type 'IntOrBool||0}
            {:Error-type :failed-predicate,
             :not integer?
             :value :blah,
             :type-sig '(IntOrBool),
             :path nil
             :sub-type 'Integer}]))
    (is (= (type-checker 'IntOrBoolOrCljs :blah {})
           [{:Error-type :failed-predicate,
             :not :MAPP,
             :value :blah,
             :type-sig '(IntOrBoolOrCljs),
             :path nil,
             :sub-type 'Cljsbuild}
            {:Error-type :failed-predicate,
             :not boolean?,
             :value :blah,
             :type-sig '(IntOrBoolOrCljs),
             :path nil,
             :sub-type 'IntOrBool||0}
            {:Error-type :failed-predicate,
             :not integer?,
             :value :blah,
             :type-sig '(IntOrBoolOrCljs),
             :path nil,
             :sub-type 'Integer}]))
    (is (= (type-checker 'IntOrBoolOrCljs {:server-port "Asdf"} {})
           [{:Error-type :failed-predicate,
             :not integer?,
             :value "Asdf",
             :type-sig '(Cljsbuild:server-port IntOrBoolOrCljs),
             :path '(:server-port)}]))
    (is (= (type-checker 'IntOrBoolOrCljs {:server-porter "Asdf"} {})
           [{:Error-type :unknown-key,
             :key :server-porter,
             :value "Asdf",
             :type-sig '(IntOrBoolOrCljs),
             :path '(:server-porter)}])))
  )

(deftest get-parents-for-type
  (with-schema (index-spec
                (spec 'Root {:figwheel (ref-schema 'Fig)
                           :cljs     (ref-schema 'Cljs)})
                (spec 'Cljs {:thing (ref-schema 'Thing)
                             :wow  (ref-schema 'Wha)
                             :intly (ref-schema 'Intly)})
                (spec 'Thing string?)
                (spec 'Yep integer?)
                (spec 'Ouch  (ref-schema 'Yep))
                (spec 'Intly {:base (ref-schema 'Ouch)
                              :count integer?})
                (spec 'Int (ref-schema 'Intly))
                (spec 'Integer (ref-schema 'Int))
                (or-spec 'Wha
                         (ref-schema 'Integer)
                         (ref-schema 'String)))
    (is (= (parents-for-type 'Yep) [[:base 'Intly]]))
    (is (= (parents-for-type 'Intly) [[:intly 'Cljs] [:wow 'Cljs]]))
    (is (empty? (parents-for-type 'Intlyy)))
    (is (= (get-paths-for-type 'Root 'Cljs) [[:cljs]]))
    (is (= (get-paths-for-type 'Root 'Yep) [[:cljs :intly :base] [:cljs :wow :base]]))))

(deftest immeditate-or
  (with-schema (index-spec
                (or-spec 'Root
                         {:wham 7}
                         {:wow 5}))
    (is (empty? (type-checker 'Root {:wow 5} {})))
    (is (empty? (type-checker 'Root {:wham 7} {})))
    (is (not-empty (type-checker 'Root {:wow 6} {})))
    (is (not-empty (type-checker 'Root {:wham 6} {}))))

  (with-schema (index-spec
                (or-spec 'Root 4 5))
    (empty? (type-checker 'Root  5 {}))
    (empty? (type-checker 'Root  4 {}))
    (not-empty (type-checker 'Root 6 {})))

  (with-schema
    (index-spec
     (or-spec 'Root string? symbol?))
    (empty? (type-checker 'Root 'asdf {}))
    (empty? (type-checker 'Root "asdf" {}))
    (type-checker 'Root 5 {})
    (is (= (type-checker 'Root 5 {})
           [{:Error-type :failed-predicate, :not string?, :value 5, :type-sig '(Root), :path nil, :sub-type 'Root||0}
            {:Error-type :failed-predicate, :not symbol?, :value 5, :type-sig '(Root), :path nil, :sub-type 'Root||1}])))
  
  (with-schema
    (index-spec (or-spec 'Root
                         5
                         string?
                         [{:wow 5}]
                         {:yep {:wow 5}}))
    (is (empty? (type-checker 'Root [{:wow 5}] {})))
    (is (empty? (type-checker 'Root {:yep {:wow 5}} {})))
    (is (empty? (type-checker 'Root "asdf" {})))
    (is (empty? (type-checker 'Root 5 {})))
    (is (= 4 (count (type-checker 'Root :blah {}))))
    )
  (with-schema (index-spec (or-spec 'Boolean :true :false))
    (is (= 2 (count (type-checker 'Boolean 5 {})))))

  (with-schema (index-spec (or-spec 'Boolean true false))
    (is (= 2 (count (type-checker 'Boolean 5 {})))))
  )

(deftest not-optional
  (with-schema (index-spec
                (tc/requires-keys 'Root :figwheel)
                (spec 'Root {:figwheel 1})
                #_(spec 'Root {:figwheel string?
                               :cljsbuild string?}))
    (is (= (type-checker 'Root {} {})
           [{:Error-type :missing-required-key,
             :path '(:figwheel),
             :key :figwheel,
             :value nil,
             :parent-value {},
             :type-sig '(Root)}]))
    )
  )

(deftest unknown-key-errors
  (with-schema (test-grammer)
    (testing "misspelled-key"
      (is (= (map
              #(dissoc % :distance-score)
              (tc/misspelled-key 'RootMap :fighweel {}
                                {:path [:fighweel]
                                 :orig-config {:fighweel {}}}))
             [{:Error :misspelled-key,
               :key :fighweel,
               :correction :figwheel,
               :confidence :high}]))
      (is (= (map
              #(dissoc % :distance-score)
              (tc/misspelled-key 'RootMap :figweel {:server-port 5}
                                 {:path [:server-port :figweel]
                                  :orig-config {:figweel {:server-port 5}}})) 
             [{:Error :misspelled-key,
               :key :figweel,
               :correction :figwheel,
               :confidence :high}]))
      (testing "child is wrong value type"
        (is (empty? (tc/misspelled-key 'RootMap :fighweel 5
                                       {:path [:fighweel]
                                        :orig-config {:fighweel 5}})))
        (is (empty? (tc/misspelled-key 'RootMap :figweel {:server-port "asdf"}
                                       {:path [:figweel]
                                        :orig-config
                                        {:figweel {:server-port "asdf"}}})))
        (is (empty? (tc/misspelled-key 'RootMap :figheel {:server-ip 5}
                                       {:path [:figheel]
                                        :orig-config
                                        {:figheel {:server-ip 5}}})))
        (is (empty? (tc/misspelled-key 'RootMap :server-port 5
                                       {:path [:server-port]
                                        :orig-config
                                        {:server-port 5}})))))
    (testing "misplaced-key"
      (is (= (tc/misplaced-key 'RootMap 'FigwheelOptions :figwheel {})
             [{:Error :misplaced-key,
               :key :figwheel,
               :correct-type '[RootMap :> FigwheelOptions],
               :correct-paths [[:figwheel]],
               :confidence :high}]))
      (is (empty? (tc/misplaced-key 'RootMap 'RootMap :figwheel {})))
      (is (= (tc/misplaced-key 'RootMap 'RootMap :crossovers 5)
             [{:Error :misplaced-key,
               :key :crossovers,
               :correct-type '[CljsBuildOptions :> CljsBuildOptions:crossovers],
               :correct-paths [[:cljsbuild :crossovers]],
               :confidence :high}])))
    (testing "mispelled-misplaced-key"
      (is (= (map #(dissoc % :distance-score)
                  (tc/misspelled-misplaced-key 'RootMap 'RootMap :crosovers 5))
           [{:Error :misspelled-misplaced-key,
             :key :crosovers,
             :correction :crossovers,
             :correct-type '[CljsBuildOptions :> CljsBuildOptions:crossovers],
             :correct-paths [[:cljsbuild :crossovers]],
             :confidence :high}])))
    
    )
  )
