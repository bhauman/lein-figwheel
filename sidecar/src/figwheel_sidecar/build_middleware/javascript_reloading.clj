(ns figwheel-sidecar.build-middleware.javascript-reloading
  (:require
   [clojure.java.io :as io]
   [cljs.build.api :as bapi]
   [figwheel-sidecar.utils :as utils])
  (:import [java.nio.file Path]))

;; live javascript reloading

;; just in case we get a URL or some such let's change it to a string first

(defn relativize-local
  "Create a relative path for the input and return the java.io.File.

  The 1-arity function use the current working directory as path to
  relativize against."
  ([^Path path]
   (relativize-local (.toPath (utils/cwd)) path))
  ([^Path other-path ^Path path]
   ;; Where this path and the given path do not have a root component, then a
   ;; relative path can be constructed. A relative path cannot be constructed
   ;; if only one of the paths have a root component. Where both paths have a
   ;; root component then it is implementation dependent if a relative path can
   ;; be constructed. If this path and the given path are equal then an empty
   ;; path is returned.
   (-> (.relativize other-path path)
       .toFile
       .getCanonicalFile)))

(defn file-locations
  "Return a vector of possible locations for js-path.

  Output-dir and js-path itself are strings and the returned locations
  will be the absolute path enclosed in java.io.File instances."
  [output-dir js-path]
  (let [js-jpath (or (utils/uri-path js-path) ;; try first if it converts from a URI
                     (.toPath (io/file js-path)))]
    (assert (.isAbsolute js-jpath)
            (format "Foreign Javascript path must be absolute, got %s instead." js-path))
    (let [cwd-local-jpath (relativize-local js-jpath)]
      (cond-> []
        (not (.isAbsolute cwd-local-jpath)) (conj (io/file output-dir cwd-local-jpath))
        ;; boot always passes the full path
        (.isAbsolute js-jpath) (conj (.toFile js-jpath))))))

;; extremely tenuous relationship, this could break easily
;; file-path is a string and it is an absolute path
(defn cljs-target-file-from-foreign [output-dir file-path]
  (->> file-path
       (file-locations output-dir)
       (filter #(.exists %))
       first))

(defn closure-lib-target-file-for-ns [output-dir namesp]
  (let [path (cljs.closure/lib-rel-path {:provides [namesp]})]
    (io/file output-dir path)))

(defn safe-js->ns [file-path]
  (:provides
   (try (bapi/parse-js-ns file-path)
        (catch Throwable e
          ;; couldn't parse js for namespace
          {}))))

(defn best-try-js-ns [state js-file-path]
  (let [provs (and (.exists (io/file js-file-path))
                   (safe-js->ns js-file-path))]
    (if-not (empty? provs)
      provs
      (let [out-file (cljs-target-file-from-foreign (:output-dir state) js-file-path)]
        (and (.exists out-file)
             (safe-js->ns out-file))))))

(defn js-file->namespaces [{:keys [output-dir] :as state} js-file-path]
  (best-try-js-ns state js-file-path))

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
