(ns figwheel-sidecar.config-test
  (:require [clojure.test :refer :all]
            [figwheel-sidecar.config :as config]))

(deftest compare-semver-test
  (is (neg? (config/compare-semver "1.0.0" "1.7.0")))
  (is (neg? (config/compare-semver "1.6.0" "1.7.0")))
  (is (zero? (config/compare-semver "1.7.0" "1.7.0")))
  (is (pos? (config/compare-semver "1.8.0" "1.7.0")))
  (is (pos? (config/compare-semver "1.10.0" "1.7.0")))
  (is (pos? (config/compare-semver "1.20.0" "1.10.0")))
  (is (pos? (config/compare-semver "2.0.0" "1.7.0")))
  (is (neg? (config/compare-semver "1" "1.7.0")))
  (is (neg? (config/compare-semver "1.7" "1.7.0")))

  (is (neg? (config/compare-semver "1.2.3-alpha1" "1.2.4")))
  (is (zero? (config/compare-semver "1.2.3-alpha1" "1.2.3")))
  (is (pos? (config/compare-semver "1.2.3-alpha1" "1.2.2")))

  (is (neg? (config/compare-semver "" "1.7.0")))
  (is (neg? (config/compare-semver "a.b.c" "1.7.0"))))
