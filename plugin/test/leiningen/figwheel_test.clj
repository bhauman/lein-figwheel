(ns leiningen.figwheel-test
  (:require
    [leiningen.figwheel :as f]
    [clojure.test :as t :refer [deftest is testing run-tests]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.java.io :as io]))

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

(defspec source-paths-bad-data
  iterations
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

(deftest update-builds-test
  (testing "vector of builds"
    (is (= (f/update-builds {:cljsbuild {:builds [{:id "dev"}
                                                  {:id "prod"}
                                                  {:id "test"}]}}
                            (fn [build]
                              (update build :source-paths conj "src")))
           {:cljsbuild {:builds [{:id           "dev"
                                  :source-paths ["src"]}
                                 {:id           "prod"
                                  :source-paths ["src"]}
                                 {:id           "test"
                                  :source-paths ["src"]}]}})))
  (testing "map of builds"
    (is (= (f/update-builds {:cljsbuild {:builds {"dev"  {}
                                                  "prod" {}
                                                  "test" {}}}}
                            (fn [build]
                              (update build :source-paths conj "src")))
           {:cljsbuild {:builds {"dev"  {:source-paths ["src"]}
                                 "prod" {:source-paths ["src"]}
                                 "test" {:source-paths ["src"]}}}})))
  (testing "edge cases around no builds"
    (testing "vector of builds"
      (is (= (f/update-builds {:cljsbuild {:builds []}}
                              (fn [x]
                                1))
             {:cljsbuild {:builds []}}))
      (is (= (f/update-builds {:cljsbuild {}}
                              (fn [x]
                                1))
             {:cljsbuild {}})))
    (testing "map of builds"
      (is (= (f/update-builds {:cljsbuild {:builds {}}}
                              (fn [x]
                                1))
             {:cljsbuild {:builds {}}}))
      (is (= (f/update-builds {:cljsbuild {}}
                              (fn [x]
                                1))
             {:cljsbuild {}})))))

(deftest add-source-paths-test
  (testing "vector of builds"
    (is (= (f/add-source-paths
             {:cljsbuild {:builds [{:id             "dev"
                                    :source-paths   ["src/cljs" "src/cljc" "dev"]
                                    :resource-paths ["resources"]}
                                   {:id             "prod"
                                    :source-paths   ["src/cljs" "src/cljc" "prod"]
                                    :resource-paths ["resources"]}]}}
             ["../util-lib/src"])
           {:cljsbuild {:builds [{:id             "dev"
                                  :source-paths   ["src/cljs" "src/cljc" "dev" "../util-lib/src"]
                                  :resource-paths ["resources"]}
                                 {:id             "prod"
                                  :source-paths   ["src/cljs" "src/cljc" "prod" "../util-lib/src"]
                                  :resource-paths ["resources"]}]}}))))

(deftest checkout-source-paths-test
  (let [cwd (.getCanonicalFile (io/file "."))]
    (testing "test project with checkouts"
      (is (= (f/checkout-source-paths
               {:root         (str cwd "/test-resources/test-project-with-checkouts")
                :source-paths ["src"]
                :cljsbuild    {:builds {:dev {:source-paths ["src"]
                                              :compiler     {:main          'core
                                                             :asset-path    "js/out"
                                                             :output-to     "resources/public/js/example.js"
                                                             :output-dir    "resources/public/js/out"
                                                             :optimizations :none}}}}})
             ["../utils-lib/src"])))
    (testing "test project with no checkouts"
      (is (= (f/checkout-source-paths
               {:root         (str cwd "/test-resources/test-project-with-no-checkouts")
                :source-paths ["src"]
                :cljsbuild    {:builds {:dev {:source-paths ["src"]
                                              :compiler     {:main          'core
                                                             :asset-path    "js/out"
                                                             :output-to     "resources/public/js/example.js"
                                                             :output-dir    "resources/public/js/out"
                                                             :optimizations :none}}}}})
             [])))))
