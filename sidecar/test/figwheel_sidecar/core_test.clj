(ns figwheel-sidecar.core-test
  (:use figwheel-sidecar.core)
  (:use clojure.test)
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]))

(deftest test-utils
  (is (.startsWith (project-unique-id) "figwheel-sidecar--")))

;; truncating to the correct server relative namspace is important
(defn remove-resource-path-tests [st]
    (is (= (relativize-resource-paths st)
           ["resources" "other-resources" "dev-resources"
            "other-resources-help/dang"]))

    (is (= (relativize-resource-paths (dissoc st :resource-paths))
           []))
    (is (= (ns-to-server-relative-path st 'example.crazy-name)
           "/js/compiled/out/example/crazy_name.js"))
    (is (= (ns-to-server-relative-path st 'example.crazy_name)
           "/js/compiled/out/example/crazy_name.js"))
    (is (= (ns-to-server-relative-path st "example.crazy_name")
           "/js/compiled/out/example/crazy_name.js"))
    (is (= (ns-to-server-relative-path st "example.crazy-name")
           "/js/compiled/out/example/crazy_name.js")))

(deftest test-remove-resource-path
  (let [root (.getCanonicalPath (io/file "."))
        st { :http-server-root "public"
            :output-dir "other-resources/public/js/compiled/out"
           :resource-paths [(str root "/resources")
                            (str root "/other-resources")
                            (str root "/dev-resources")
                            (str root "/other-resources-help/dang")]}]
    (remove-resource-path-tests st)
    (remove-resource-path-tests
     (assoc st
       :output-dir       "resources/public/js/compiled/out"))
    (remove-resource-path-tests
     (assoc st
       :output-dir       "other-resources-help/dang/public/js/compiled/out"))    
    (remove-resource-path-tests
     (merge st {:http-server-root "public/house"
                :output-dir       "other-resources/public/house/js/compiled/out"}))

    ;; make sure it works if not given abasolute paths
    (remove-resource-path-tests
     (merge st {:resource-paths [(str "resources")
                                 (str "other-resources")
                                 (str "dev-resources")
                                 (str "other-resources-help/dang")] }))
    ))


(defn windows-pather [s]
  (string/replace s "/" "\\"))

(deftest test-remove-resource-windows-path
  (let [root (windows-pather (.getCanonicalPath (io/file ".")))
        st { :http-server-root "public"
             :resource-paths [(str root "\\resources")
                              (str root "\\other-resources")
                              (str root "\\dev-resources")
                             (str root "\\other-resources-help/dang")]}]
    (is (= (remove-resource-path st "resources\\public\\js\\example\\core.js")
           "/js/example/core.js"))
    (is (= (remove-resource-path st "resources\\public\\css\\style.css")
           "/css/style.css"))
    ))

#_(run-tests)
