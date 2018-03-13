(ns figwheel-sidecar.utils.fuzzy-test
  (:require [clojure.test :refer :all]
            [figwheel-sidecar.utils.fuzzy :as fuzzy]))

(deftest similar-key-test
  (is (true? (fuzzy/similar-key 0 :GFSD :GSFD)))
  (is (false? (fuzzy/similar-key 0 :figwheel :figwh)))
  (is (true? (fuzzy/similar-key 0 :fighweel :figwhe)))
  (is (true? (fuzzy/similar-key 0 :fighweel :figwhee)))
  (is (true? (fuzzy/similar-key 0 :figwheel :figwheeler))))

(deftest levenshtein-test
  (is (= 2 (fuzzy/levenshtein "figwheel" "figwheeler")))
  (is (= 2 (fuzzy/levenshtein "figwheel" "figwheele1")))
  (is (= 2 (fuzzy/levenshtein "figwheel" "figwhele")))
  (is (= 0 (fuzzy/levenshtein "figwheel" "figwheel"))))
