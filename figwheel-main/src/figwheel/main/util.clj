(ns figwheel.main.util
  (:require
   [clojure.java.io :as io])
  (:import
   [java.nio.file.Paths]))

(defn ->path [s & args]
  (java.nio.file.Paths/get ^String s (into-array String args)))

(defn path-parts [& args]
  (mapv str (apply ->path args)))

(defn relativized-path-parts [path]
  (let [local-dir-parts (path-parts (System/getProperty "user.dir"))
        parts (path-parts (.getCanonicalPath (io/file path)))]
    [local-dir-parts parts]
    (when (= local-dir-parts (take (count local-dir-parts) parts))
      (drop (count local-dir-parts) parts))))

#_(relativized-path-parts (.getCanonicalPath (io/file "src/figwheel/main.clj")))

(defn require? [symbol]
  (try
    (require symbol)
    true
    (catch Exception e
      #_(println (.getMessage e))
      #_(.printStackTrace e)
      false)))

(defn require-resolve-var [handler]
  (when handler
    (if (fn? handler)
      handler
      (let [h (symbol handler)]
        (when-let [ns (namespace h)]
          (when (require? (symbol ns))
            (when-let [handler-var (resolve h)]
              handler-var)))))))
