(ns leiningen.figwheel-test
  (:require
   [leiningen.figwheel :as f]
   [clojure.test :as t :refer [deftest is testing run-tests]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test.check.clojure-test :refer [defspec]]))

(def iterations 10)

(defspec command-like?-handles-arbitrary-data
  iterations
  (prop/for-all
   [v gen/any]
   (not (f/command-like? v))))

(defspec command-like-detects-string-that-
  iterations
  (prop/for-all
   [v (gen/fmap #(str ":" %) gen/string)]
   (f/command-like? v)))

(defspec normalize-data-can-handle-arbi
  iterations
  (prop/for-all
   [v gen/any]
   (= (f/normalize-data v [1])
      {:figwheel-options {}
       :all-builds []
       :build-ids [1]})))

(defspec normalize-data-can-handle-arbi-figwheel-edn
  iterations
  (prop/for-all
   [v gen/any]
   (with-redefs [f/figwheel-edn (fn [] v)]
     (= (f/normalize-data nil [1])
        {:figwheel-options (if (map? v) v {})
         :all-builds []
         :build-ids [1]}))))

(defspec normalize-data-map-or-vector-builds1
  iterations
  (prop/for-all
   [v (gen/hash-map
       :cljsbuild
       (gen/hash-map
        :builds
        gen/any)
       :figwheel gen/any)]
   (f/normalize-data v [])))

(defspec normalize-data-map-or-vector
  iterations
  (prop/for-all
   [v (gen/hash-map
       :cljsbui ;; testing spelling repair
       (gen/hash-map
        :builds
        (gen/one-of [(gen/map
                      (gen/one-of [gen/keyword
                                   gen/symbol
                                   gen/string])
                      (gen/hash-map :source-paths (gen/vector gen/string)))
                     (gen/vector
                      (gen/hash-map :source-paths (gen/vector gen/string)
                                    :id (gen/one-of [gen/keyword
                                                     gen/symbol
                                                     gen/string])))]))
       :figwheel gen/any)]
   (let [nd (f/normalize-data v [])]
     (and (vector? (:all-builds nd))
          (= (->> v :cljsbui :builds count)
             (count (:all-builds nd)))))))

(defspec normalize-data-map-or-vector1
  iterations
  (prop/for-all
   [v (gen/hash-map
       :cljsbuild
       (gen/hash-map
        :builds
        (gen/one-of [(gen/map
                      (gen/one-of [gen/keyword
                                   gen/symbol
                                   gen/string])
                      (gen/hash-map :source-paths (gen/vector gen/string)))
                     (gen/vector
                      (gen/hash-map :source-paths (gen/vector gen/string)
                                    :id (gen/one-of [gen/keyword
                                                     gen/symbol
                                                     gen/string])))]))
       :cljsbuil gen/any  ;; testing close spelling
       :figwheel gen/any)]
   (let [nd (f/normalize-data v [])]
     (and (vector? (:all-builds nd))
          (= (->> v :cljsbuild :builds count)
             (count (:all-builds nd)))))))

(defspec source-paths-for-class-path-arbi
  iterations
  (prop/for-all
   [v (gen/map (gen/elements [:figwheel-options :all-builds :build-ids])
               gen/any)]
   #_(source-paths-for-class-path v)
   (vector? (f/source-paths-for-classpath v))))

;; super slow
(defspec source-paths-bad-data
  10
  (prop/for-all
   [v (gen/map (gen/return :all-builds)
               (gen/vector (gen/map (gen/return :source-paths)
                                    (gen/one-of [(gen/vector gen/any)
                                                 gen/any]))))]
   (vector? (f/source-paths-for-classpath v))))

(defspec source-paths-bad-data2
  iterations
  (prop/for-all
   [v (gen/map (gen/return :all-builds)
               (gen/vector (gen/hash-map
                            :id (gen/one-of [gen/keyword
                                             gen/symbol
                                             gen/string])
                            :source-paths (gen/vector gen/string))))]
   (= (count (distinct (->> v :all-builds (mapcat :source-paths))))
      (count (f/source-paths-for-classpath v)))))


#_(gen/sample (gen/map (gen/return :all-builds)
               (gen/vector (gen/map (gen/return :source-paths)
                                    (gen/vector gen/string)))))
