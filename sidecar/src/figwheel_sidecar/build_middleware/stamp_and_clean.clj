(ns figwheel-sidecar.build-middleware.stamp-and-clean
  (:require
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.config :refer [on-stamp-change]]
   [clojure.java.io :as io]))

;; Minimal protection against corrupt builds

;; While minimal this will prevent plenty of headaches caused by
;; corrupted build :output-dir directories

;; This is a temporary solution until the ClojureScript compiler supports
;; something like a :signature key

(def user-dir-hash  (.hashCode (System/getProperty "user.dir")))

;; could hash all jar file names??
(def classpath-hash (.hashCode (System/getProperty "java.class.path")))

(defn stamp-file [build-config]
  (let [output-dir (io/file (or (-> build-config :build-options :output-dir) "out"))]
    (if (.isAbsolute output-dir)
      (io/file (.getCanonicalPath output-dir) ".figwheel-compile-stamp")
      (io/file (or (System/getProperty "user.dir")
                   (io/file "."))
               output-dir
               ".figwheel-compile-stamp"))))

;; this provides a simple map and stable order for hashing
;; TODO this should return a seq of map entries
(defn options-that-affect-build-cache [build-config]
  (-> build-config
      (select-keys [:static-fns :optimize-constants
                    :elide-asserts :target])
      (assoc
       :build-id     (:id build-config)
       :source-paths (:source-paths build-config)
       :classpath    classpath-hash)))

(defn current-stamp-signature [build-config]
  (->> build-config
       options-that-affect-build-cache
       (into (sorted-map))
       pr-str
       (.hashCode)
       str))

(defn create-deps-stamp [build-config]
  {:file      (stamp-file build-config)
   :signature (current-stamp-signature build-config)})

#_(stamp! {:id "howw" :cow 3 :build-options {}})
#_(stamp-matches? {:id "howw" :cow 2 :build-options {}})

(defn hook [build-fn]
  (fn [{:keys [build-config] :as build-state}]
    (on-stamp-change
     (create-deps-stamp build-config)
     #(do
        (println "Figwheel: Cleaning build -" (:id build-config))
        (utils/clean-cljs-build* build-config)))
    (build-fn build-state)))
