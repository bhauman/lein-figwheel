(ns figwheel-sidecar.build-middleware.clj-reloading
  (:require
   [figwheel-sidecar.utils :as utils]
   [cljs.build.api :as bapi]
   [cljs.env :as env]
   [clojure.java.io :as io]))

;; live relading clj macros is a tough problem and we should think
;; about either backing off or doing it more correctly say with
;; tools.namespace

;; TODO refactor

;; TODO we should use tools.analyzer
(defn get-clj-ns [x]
  (-> x :source-file utils/get-ns-from-source-file-path))

(defn get-clj-namespaces [file-resources]
  (map get-clj-ns file-resources))

;; this gets cljs dependant ns for macro files
(defn macro-dependants [macro-file-resources]
  (let [namespaces (get-clj-namespaces macro-file-resources)]
    (bapi/cljs-dependents-for-macro-namespaces namespaces)))

(defn mark-known-dependants-for-recompile! [opts file-resources]
  (let [ns-syms (macro-dependants file-resources)]
    (doseq [ns-sym ns-syms]
      (bapi/mark-cljs-ns-for-recompile! ns-sym (:output-dir opts)))
    ns-syms))

(defn macro-file?
  [f] (.contains (slurp (:source-file f)) "(defmacro"))

(defn annotate-macro-file [f]
  (assoc f :macro-file? (macro-file? f)))

(defn get-files-to-reload [opts changed-clj-files]
  ;; :reload-non-macro-clj-files defaults to true
  (if ((fnil identity true) (:reload-non-macro-clj-files opts))
    changed-clj-files
    (filter :macro-file? changed-clj-files)))

(defn relevant-macro-files [clj-files-fn changed-clj-files]
  (let [non-macro-clj? (first (filter #(not (:macro-file? %)) changed-clj-files))]
    (filter :macro-file?
            (if non-macro-clj? (clj-files-fn)
                changed-clj-files))))

(defn clj-files-in-dirs [dirs]
  (let [all-files (mapcat file-seq (filter #(and (.exists %)
                                                 (.isDirectory %))
                                           (map io/file dirs)))]
    (map (fn [f] {:source-file f })
         (filter #(let [name (.getName ^java.io.File %)]
                    (and (or (.endsWith name ".clj")
                             (.endsWith name ".cljc"))
                         (not= \# (first name))
                         (not= \. (first name))))
                 all-files))))

(defn handle-clj-source-reloading [{:keys [source-paths build-options compiler-env] :as build-config} changed-clj-files]
  (let [build-options (or build-options (:compiler build-config))
        changed-clj-files (keep
                           (fn [f]
                             (when f
                               (let [f (io/file f)]
                                 (annotate-macro-file
                                  {:source-file f}))))
                           changed-clj-files)
        files-to-reload (get-files-to-reload build-options changed-clj-files)]
    (when (not-empty changed-clj-files)
      (doseq [clj-file files-to-reload]
        ;; this could be a problem if the file isn't in the require
        ;; chain
        ;; it will be loaded anyway
        (load-file (.getCanonicalPath (:source-file clj-file))))
      (let [rel-files (relevant-macro-files
                       (fn [] (map annotate-macro-file (clj-files-in-dirs source-paths)))
                       changed-clj-files)]
        (env/with-compiler-env compiler-env
          (mark-known-dependants-for-recompile! build-options rel-files))))))

(defn default-config [{:keys [reload-clj-files] :as figwheel-server}]
  (cond
    (false? reload-clj-files) false
    (or (true? reload-clj-files)
          (not (map? reload-clj-files))) 
    {:cljc true :clj true}
    :else reload-clj-files))

(defn suffix-conditional [config]
  #(reduce-kv
    (fn [accum k v]
      (or accum
          (and v
               (.endsWith % (str "." (name k))))))
    false
    config))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (let [reload-config (default-config figwheel-server)]
      (if-let [changed-clj-files (and
                                  reload-config
                                  (not-empty
                                   (filter (suffix-conditional reload-config)
                                           changed-files)))]
        (let [additional-changed-ns' (handle-clj-source-reloading build-config changed-clj-files)]
          (build-fn (update-in
                     build-state
                     [:additional-changed-ns]
                     concat
                     additional-changed-ns')))
        (build-fn build-state)))))
