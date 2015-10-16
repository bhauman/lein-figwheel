(ns figwheel-sidecar.build-hooks.javascript-reloading
  (:require
   [clojure.java.io :as io]
   [cljs.build.api :as bapi]
   [cljs.closure]))

;; live javascript reloading

(defn get-foreign-lib [{:keys [foreign-libs]} file-path]
  (when foreign-libs
    (let [file (io/file file-path)]
      (first (filter (fn [fl]
                       (= (.getCanonicalPath (io/file (:file fl)))
                          (.getCanonicalPath file)))
                     foreign-libs)))))

(defn js-file->namespaces [{:keys [foreign-libs] :as state} js-file-path]
  (if-let [foreign (get-foreign-lib state js-file-path)]
    (:provides foreign)
    (:provides (bapi/parse-js-ns js-file-path))))

(defn cljs-target-file-from-foreign [output-dir file-path]
  (io/file (str output-dir java.io.File/separator (.getName (io/file file-path)))))

(defn closure-lib-target-file-for-ns [output-dir namesp]
  (let [path (cljs.closure/lib-rel-path {:provides [namesp]})]
    (io/file output-dir path)))

(defn get-js-copies [{:keys [output-dir] :as state} changed-js]
  (keep
   (fn [f]
     (if-let [foreign (get-foreign-lib state f)]
       {:output-file (cljs-target-file-from-foreign output-dir f)
        :file f}
       (when-let [namesp (first (js-file->namespaces state f))]
         {:output-file (closure-lib-target-file-for-ns output-dir namesp)
          :file f})))
   changed-js))

(defn make-copies [copies]
  (doseq [{:keys [file output-file]} copies]
    (spit output-file (slurp file))))

(defn copy-changed-js [state changed-js]
  ;; TODO there is an easy way to do this built into the clojurescript
  ;; compiler source
  ;; the idea here is we are only copying files that make sense to
  ;; copy i.e. they have a provide
  (when-not (empty? changed-js)
    (make-copies (get-js-copies state changed-js))))

(defn build-hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (if-let [changed-js-files (filter #(.endsWith % ".js") changed-files)]
      (let [build-options (or (:build-options build-config) (:compiler build-config))
            additional-changed-ns   ;; add in js namespaces
            (mapcat (partial js-file->namespaces build-options)
                    changed-js-files)]
        (copy-changed-js build-options changed-js-files)
        (build-fn (update-in
                   build-state [:additional-changed-ns] concat additional-changed-ns)))
      (build-fn build-state))))
