(ns figwheel-sidecar.build-middleware.javascript-reloading
  (:require
   [clojure.java.io :as io]
   [cljs.build.api :as bapi]
   [figwheel-sidecar.utils :as utils]
   [clojure.pprint :as pp]))

;; live javascript reloading

;; extremely tenuous relationship, this could break easily
;; file-path is a string and it is an absolute path
(defn cljs-target-file-from-foreign [output-dir file-path]
  (first (filter #(.exists %)
                 ;; try the projected location
                 [(io/file output-dir (utils/relativize-local file-path))
                  (io/file output-dir (.getName (io/file file-path)))])))

(defn closure-lib-target-file-for-ns [output-dir namesp]
  (let [path (cljs.closure/lib-rel-path {:provides [namesp]})]
    (io/file output-dir path)))

(defn safe-js->ns [foreign-libs file-path]
  (:provides
   ;; first check if a foreign-lib addresses this file
   (or (some->> foreign-libs
                (filter #(.endsWith file-path (:file %)))
                first)
       (try (bapi/parse-js-ns file-path)
            (catch Throwable e
              ;; couldn't parse js for namespace
              {})))))

(defn best-try-js-ns [state foreign-libs js-file-path]
  (let [provs (and (.exists (io/file js-file-path))
                   (safe-js->ns foreign-libs js-file-path))]
    (if-not (empty? provs)
      provs
      (if-let [out-file (cljs-target-file-from-foreign (:output-dir state) js-file-path)]
        (and (.exists out-file)
             (safe-js->ns foreign-libs out-file))))))

(defn js-file->namespaces [{:keys [foreign-libs output-dir] :as state} js-file-path]
  (best-try-js-ns state foreign-libs js-file-path))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (if-let [changed-js-files (filter #(.endsWith % ".js") changed-files)]
      (let [build-options (or (:build-options build-config) (:compiler build-config))
            additional-changed-ns ;; add in js namespaces
            (mapcat (partial js-file->namespaces build-options)
                    changed-js-files)]
        (build-fn (update-in
                   build-state [:additional-changed-ns] concat additional-changed-ns)))
      (build-fn build-state))))
