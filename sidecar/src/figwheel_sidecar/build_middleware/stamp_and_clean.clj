(ns figwheel-sidecar.build-middleware.stamp-and-clean
  (:require
   [figwheel-sidecar.utils :as utils]
   [clojure.java.io :as io]))

;; some minimal protection against corrupt builds
;; this is really only intended to run once at the start
;; of an autobuild session but its fast enough to run every time

(def user-dir-hash  (.hashCode (System/getProperty "user.dir")))

(def classpath-hash (.hashCode (System/getProperty "java.class.path")))

(defn stamp-file [build-config]
  (io/file
   (System/getProperty "java.io.tmpdir")
   (str
    (hash
     {:build-id  (.hashCode (str (:id build-config)))
      :root-path user-dir-hash})
    ".txt")))

;; this provides a simple map and stable order for hashing
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

#_(count (into #{}  (take 100000 (repeatedly #(current-stamp-signature t)))))

(defn stamp! [build-config]
  (println "Stamping!")
  (println "Stamp file" (stamp-file build-config))
  (println "Stamp" (current-stamp-signature build-config))
  (spit (stamp-file build-config)
        (current-stamp-signature build-config)))

(defn stamp-value [build-state]
  (let [sf (stamp-file build-state)]
    (when (.exists sf) (slurp sf))))

(defn stamp-matches? [build-config]
  (println "Current Stamp " (current-stamp-signature build-config))
  (println "Previous Stamp" (stamp-value build-config))
  (= (current-stamp-signature build-config)
     (stamp-value build-config)))

#_(stamp! {:id "howw" :cow 3 :build-options {}})
#_(stamp-matches? {:id "howw" :cow 2 :build-options {}})

(defn hook [build-fn]
  (fn [{:keys [build-config] :as build-state}]
    (when-not (stamp-matches? build-config)
      (println "Figwheel: Cleaning build -" (:id build-config))
      (utils/clean-cljs-build* build-config))
    (stamp! build-config)
    (build-fn build-state)))
