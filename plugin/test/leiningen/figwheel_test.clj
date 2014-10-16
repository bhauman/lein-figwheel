(ns leiningen.figwheel-test
  (:require
   [leiningen.figwheel :as fig]
   [clojure.test :refer [testing is deftest]]))

(deftest test-cljs-change-server-watch-dirs
  (let [proj {:cljsbuild { :builds [{:compiler { :output-to "there"
                                                :output-dir "hello" }}] }}]
    (is ["hello" "there"] (fig/cljs-change-server-watch-dirs proj))))

(deftest optimizations-none-test
  (let [build {:compiler {:optimizations :none}}
        build-no {:compiler {:optimizations :whew}}
        build-no2 {}
        build-no3 nil]
    (is (fig/optimizations-none? build))
    (is (not (fig/optimizations-none? build-no)))
    (is (not (fig/optimizations-none? build-no2)))
    (is (not (fig/optimizations-none? build-no3)))))

(deftest resources-pattern-str-test
  (is (= "()/public" (fig/resources-pattern-str {})))
  (is (= "(fine/marty|fine/joe)/public"
         (fig/resources-pattern-str
          {:root "hello"
           :resource-paths ["hello/fine/marty" "hello/fine/joe"]})))
  (is (= "(fine/marty|fine/joe)/public-like"
         (fig/resources-pattern-str
          {:http-server-root "public-like"
           :root "hello"
           :resource-paths ["hello/fine/marty" "hello/fine/joe"]}))))

(deftest output-dir-in-resources-root-test
  (let [opts {:root "/hello"
              :resource-paths ["/hello/marty" "/hello/joe"]}]
    (is (fig/output-dir-in-resources-root? (assoc opts :output-dir "marty/public/hi")))
    (is (not (fig/output-dir-in-resources-root? (assoc opts :output-dir "marty/something/hi"))))))

(deftest map-to-vec-builds-test
  (is (= [1 2 3 4]
         (fig/map-to-vec-builds [1 2 3 4])))
  (is (= [{:id "hello"} {:id "there"}]
         (fig/map-to-vec-builds {"hello" {} "there" {}})))
  (is (= [{:id "hello"} {:id "there"}]
         (fig/map-to-vec-builds {:hello {} :there {}}))))

(deftest narrow-to-one-build*-test
  (is (= [{:id "there"}]
         (fig/narrow-to-one-build* {:hello {} :there {}} "there")))
  (is (= [{:id "hello"}]
         (fig/narrow-to-one-build* {:hello {} :there {}} "hello")))
  (is (= [{:id "hello" :compiler {:optimizations :none}}]
         (fig/narrow-to-one-build* [{:id "hello"
                                     :compiler { :optimizations :none } }
                                    {:id "there"
                                     :compiler { :optimizations :none }}]
                              nil)))
  (is (= [ nil ]
         (fig/narrow-to-one-build* [{:id "hello"} {:id "there"}] nil)))
  (is (= [ nil ]
         (fig/narrow-to-one-build* [{:id "hello"} {:id "there"}] "bad-id"))))

(deftest narrow-to-one-build-test
  (is (= { :cljsbuild { :builds [{:id "hello"}]} }
         (fig/narrow-to-one-build { :cljsbuild { :builds {:hello {} :there {}}} }
                              "hello"))))
