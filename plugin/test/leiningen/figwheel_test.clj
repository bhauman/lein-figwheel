(ns leiningen.figwheel-test
  (:require
   [leiningen.figwheel :as fig]
   [clojure.java.io :as io]
   [clojure.test :refer [testing is deftest run-tests]]))

(deftest optimizations-none-test
  (let [build {:compiler {:optimizations :none}}
        build-no {:compiler {:optimizations :whew}}
        build-no2 {}
        build-no3 nil]
    (is (fig/optimizations-none? build))
    (is (not (fig/optimizations-none? build-no)))
    (is (not (fig/optimizations-none? build-no2)))
    (is (not (fig/optimizations-none? build-no3)))))

(deftest output-dir-in-resources-root-test
  (let [root (.getCanonicalPath (io/file "."))
        opts {:root root
              :http-server-root "public"
              :resource-paths [(str root "/marty")
                               (str root "/joe")]}]
    (is (fig/output-dir-in-resources-root? {:output-dir "marty/public/hi"} opts))
    (is (not (fig/output-dir-in-resources-root? {:output-dir "marty/something/hi"}
                                                opts)))))

(deftest map-to-vec-builds-test
  (is (= [1 2 3 4]
         (fig/map-to-vec-builds [1 2 3 4])))
  (is (= [{:id "hello"} {:id "there"}]
         (fig/map-to-vec-builds {"hello" {} "there" {}})))
  (is (= [{:id "hello"} {:id "there"}]
         (fig/map-to-vec-builds {:hello {} :there {}}))))

(deftest narrow-to-one-build*-test
  (is (= [{:id "there"}]
         (fig/narrow-builds* {:hello {} :there {}} ["there"])))

  (is (= [{:id "there"}]
         (fig/narrow-builds* {:hello {} :there {}} ["there" "here"])))
  
  (is (= [{:id "there"}]
         (fig/narrow-builds* {:hello {} :there {}} ["here" "there"])))
    
  (is (= {:id "there"}
         (first (fig/narrow-builds* {:hello {} :there {}} ["there" "hello"]))))

  (is (= {:id "hello"}
         (first (fig/narrow-builds* {:hello {} :there {}} ["hello" "there"]))))
  
  (is (= [{:id "there"}]
         (fig/narrow-builds* [{:id :hello} {:id :there}] ["there"])))

  (is (= (set [{:id "there"} {:id "hello"}])
         (set (fig/narrow-builds* {:hello {} :there {}} ["there" "hello"]))))
  (is (= [{:id "hello"}]
         (fig/narrow-builds* {:hello {} :there {}} ["hello"])))
  (is (= [{:id "hello" :compiler {:optimizations :none}}]
         (fig/narrow-builds* [{:id "hello"
                               :compiler { :optimizations :none } }
                              {:id "there"
                               :compiler { :optimizations :none }}]
                             nil)))
  (is (= []
         (fig/narrow-builds* [{:id "hello"} {:id "there"}] nil)))
  (is (= []
         (fig/narrow-builds* [{:id "hello"} {:id "there"}] ["bad-id"]))))

(deftest narrow-to-one-build-test
  (is (= { :cljsbuild { :builds [{:id "hello"}]} }
         (fig/narrow-builds { :cljsbuild { :builds {:hello {} :there {}}} }
                            ["hello"]))))

(run-tests)
