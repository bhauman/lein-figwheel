(ns figwheel-sidecar.config-check.validate-config-test
  (:require
   [figwheel-sidecar.config-check.validate-config :as vc]
   [figwheel-sidecar.config-check.type-check :as tc]
   [clojure.test :as t :refer [deftest is testing run-tests]]))


(defn check-one [rules root value]
  (tc/with-schema rules
    (tc/type-check-one-error root value)))

(defn check [rules root value]
  (tc/with-schema rules
    (tc/type-check root value)))

(def check-reg #(check-one (vc/validate-regular-rules %) 'RootMap %))

(defn check-reg-keys [value keys expected-values]
  (is (= expected-values ((apply juxt keys) (check-reg value)))))

(defn is-valid [data]
  (is (nil? (check-reg data))))

(deftest checking-config-errors-1
  (check-reg-keys {} [:Error-type :key] [:missing-required-key :cljsbuild])
  (check-reg-keys {:cljsbuild []} [:Error-type :not] [:failed-predicate :MAPP])
  (check-reg-keys {:cljsbuil {}}
                  [:Error :key :corrections]
                  [:misspelled-key :cljsbuil [:cljsbuild]])
  (check-reg-keys {:cljsbuild {}}
                  [:Error-type :key]
                  [:missing-required-key :builds])
  (check-reg-keys {:cljsbuild {:builds {}}}
                  [:Error-type :key]
                  [:should-not-be-empty :builds])
  (check-reg-keys {:cljsbuild {:builds {:dev {}}}}
                  [:Error-type :key]
                  [:missing-required-key :source-paths])
  (check-reg-keys {:cljsbuild {:builds {:dev {:source-paths {}}}}}
                  [:Error-type :key]
                  [:missing-required-key :compiler])

  (check-reg-keys {:cljsbuild {:builds {:dev {:source-paths {}
                                              :compiler {}}}}}
                  [:Error-type :key]
                  [:should-not-be-empty :source-paths])

  (check-reg-keys {:cljsbuild {:builds {:dev {:source-paths [1]
                                              :compiler {}}}}}
                  [:Error-type :not]
                  [:failed-predicate string?])
  
  (is-valid {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                        :compiler {}}}}})

  (check-reg {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                              :compiler {:warnings {:fn-var 2}}}}}})
  (check-reg-keys {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                              :compiler {:warnings {:fn-var 2}}}}}}
                  [:Error :not]
                  [:failed-predicate true])
  
  (is-valid {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                       :compiler {:warnings {:fn-var false}}}}}})

  (is-valid {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                       :compiler {:warnings {:fn-var true}}}}}})

  (is-valid {:cljsbuild {:builds {:dev {:source-paths ["src"]
                                         :compiler {}}}}
              :figwheel {:reload-clj-files {:clj true}}})
  )
