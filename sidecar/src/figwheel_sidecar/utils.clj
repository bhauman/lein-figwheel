(ns figwheel-sidecar.utils
  (:require
   [clojure.java.io :as io]
   [cljs.analyzer :as ana]
   [cljs.env]
   [clojure.string :as string]))

(def sync-agent (agent {}))

(defn sync-exec [thunk]
  (send-off sync-agent (fn [v]
                         (try
                           (thunk)
                           (catch Exception e
                             ;; TODO better execption reporting
                             (println (.message e))))
                         v)))

(defn clean-cljs-build* [{:keys [output-to output-dir]}]
  (when (and output-to output-dir)
    (doseq [file (cons (io/file output-to)
                       (reverse (file-seq (io/file output-dir))))]
      (when (.exists file) (.delete file)))))

(defn require? [symbol]
  (try (require symbol) true (catch Exception e false)))

(defn require-resolve-handler [handler]
  (when handler
    (if (fn? handler)
      handler
      (let [h (symbol handler)]
        (when-let [ns (namespace h)]
          (when (require? (symbol ns))
            (when-let [handler-var (resolve h)]
              @handler-var)))))))

#_(require-resolve-handler figwheel-sidecar.components.cljs-autobuild/figwheel-build)

;; TODO should use tools.analyzer
(defn get-ns-from-source-file-path
  "Takes a project relative file path and returns an underscored clojure namespace.
  .ie a file that starts with (ns example.path-finder) -> example.path_finder"
  [file-path]
  (try
    (when (.exists (io/file file-path))
      (with-open [rdr (io/reader file-path)]
        (let [forms (ana/forms-seq* rdr file-path)]
          (second (first forms)))))
    (catch java.lang.RuntimeException e
      nil)))

(defn underscore [s] (string/replace s "-" "_"))

(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(let [root (norm-path (.getCanonicalPath (io/file ".")))]
  (defn remove-root-path 
    "relativize to the local root just in case we have an absolute path"
    [path]
    (string/replace-first (norm-path path) (str root "/") "")))

(defn add-compiler-env [build]
  (let [build-options (or (:build-options build)
                          (:compiler build))]
    (assoc build
           :build-options build-options
           :compiler-env (cljs.env/default-compiler-env build-options))))
