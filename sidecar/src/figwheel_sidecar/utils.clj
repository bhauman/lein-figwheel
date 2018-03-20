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
                             (println (.getMessage e))
                             ))
                         v)))

(def name-like? (some-fn string? (partial instance? clojure.lang.Named)))

(defn clean-cljs-build* [{:keys [output-to output-dir]}]
  (when (and output-to output-dir)
    (doseq [file (cons (io/file output-to)
                       (reverse (file-seq (io/file output-dir))))]
      (when (.exists file) (.delete file)))))

(defmacro bind-logging [log-writer & body]
  `(if ~log-writer
     (binding [*out* ~log-writer
               *err* ~log-writer]
       ~@body)
     (do
       ~@body)))

(defn require? [symbol]
  (try
    (require symbol)
    true
    (catch Exception e
      #_(println (.getMessage e))
      #_(.printStackTrace e)
      false)))

(defn silent-fail-require! [symbol]
  (try
    (require symbol)
    true
    (catch Exception e
      false)))

(defn require-resolve-handler [handler]
  (when handler
    (if (fn? handler)
      handler
      (let [h (symbol handler)]
        (when-let [ns (namespace h)]
          (when (require? (symbol ns))
            (when-let [handler-var (resolve h)]
              handler-var)))))))

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

(defn relativize-local [path]
  (.getPath
   (.relativize
    (.toURI (io/file (.getCanonicalPath (io/file "."))))
    ;; just in case we get a URL or some such let's change it to a string first
    (.toURI (io/file (str path))))))

(defmethod print-method ::compiler-env [o ^java.io.Writer w]
   (.write w "#CompilerEnv{}"))

(defn compiler-env
  "Creates a cljs.env compiler env that can be safely printed."
  [build-options]
  (let [env-atom (cljs.env/default-compiler-env build-options)]
    (swap! env-atom #(vary-meta % assoc :type ::compiler-env))
    env-atom))

(defmacro slow-output [& body]
  `(let [s# (new java.io.StringWriter)
         [lines# result#]
         (binding [*out* s#]
           (let [res# (do ~@body)]
             [(when-not (string/blank? (str s#))
                (string/split (str s#) #"\n"))
              res#]))]
     (doseq [line# lines#]
       (println line#)
       (Thread/sleep 15))
     result#))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))
