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

(defn foreign-libs-provides [foreign-libs file-path]
  (some->> foreign-libs
           (filter #(.endsWith file-path (:file %)))
           first
           :provides
           not-empty))

(defn safe-js->ns [file-path]
  (try (some-> (bapi/parse-js-ns file-path)
               :provides
               not-empty)
       (catch Throwable e
         ;; couldn't parse js for namespace
         nil)))

(defn best-try-js-ns [state foreign-libs js-file-path]
  (letfn [(get-provides [js-path]
            (and (.exists (io/file js-path))
                 (or
                  (safe-js->ns js-path)
                  (foreign-libs-provides foreign-libs js-path))))]
    (if-let [provs (get-provides js-file-path)]
      provs
      (if-let [out-file (cljs-target-file-from-foreign (:output-dir state) js-file-path)]
        (get-provides out-file)))))

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
