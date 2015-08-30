(ns figwheel-sidecar.core-test
  (:use [clojure.test]
        [figwheel-sidecar.core])
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [figwheel-sidecar.config :refer [relativize-resource-paths]]))

(deftest test-utils
  (is (.startsWith (project-unique-id) "figwheel-sidecar--")))

;; truncating to the correct server relative namspace is important
(defn remove-resource-path-tests [st]
    (is (= (relativize-resource-paths st)
           ["resources" "other-resources" "dev-resources"
            "other-resources-help/dang"]))

    (is (= (relativize-resource-paths (dissoc st :resource-paths))
           [])))

(deftest test-atom?
  (let [not-an-atom "foo"
        an-atom (atom {})]
    (is (atom? an-atom))
    (is (not (atom? not-an-atom)))))

#_(run-tests)
