(ns figwheel-sidecar.build-middleware.file-reloading-test
  (:require [clojure.java.io :as io]
            [figwheel-sidecar.utils :as utils]
            [figwheel-sidecar.build-middleware.javascript-reloading :as js-reloading]
            [clojure.test :as test :refer [deftest is testing]])
  (:import [java.nio.file Paths]))


(deftest javascript-file-locations
  (let [output-dir (str (Paths/get "/home" (into-array ["user" ".boot" ".cache" "src" "main.out"])))
        file (str (Paths/get "/home" (into-array ["user" ".boot" ".cache" "src" "test-namespace" "core.js"])))
        locations (js-reloading/file-locations output-dir file)]
    (is (= [file] (map str locations)) "Absolute boot-like path should return absolute file (without throwing, see issue #536)"))

  ;; relative to the current working dir (similar use case: node_modules)
  (let [output-dir (str (Paths/get "resources" (into-array ["public" "js" "compiled" "out"])))
        file (str (io/file (utils/cwd) (.toFile (Paths/get "resources" (into-array [ "public" "js" "compiled" "out" "test-namespace" "core.js"])))))
        locations (js-reloading/file-locations output-dir file)]
    (is (= [file] (map str locations)) "if already relative to cwd, should return itself."))

  (let [file (str (Paths/get "reagent" (into-array ["core.js"])))]
    (is (thrown? java.lang.AssertionError (js-reloading/file-locations "" file)) "if the js-path is relative, it should throw an assertion error"))

  ;; Test with file:/ output-dir
  (let [output-dir (str (Paths/get "resources" (into-array ["public" "js" "compiled" "out"])))
        file "file:///home/user/project-root/resources/public/js/compiled/out/reagent/core.js"
        locations (js-reloading/file-locations output-dir file)]
    (is (= [(-> file utils/uri-path str)] (map str locations)))))
