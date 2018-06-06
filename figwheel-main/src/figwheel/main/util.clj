(ns figwheel.main.util
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [cljs.util]
   [cljs.build.api :as bapi])
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
        (or (try (resolve h) (catch Throwable t nil))
            (when-let [ns (namespace h)]
              (when (require? (symbol ns))
                (when-let [handler-var (resolve h)]
                  handler-var))))))))

(defn rebel-readline? []
  (require-resolve-var 'rebel-readline.core/read-line))

(defn static-classpath []
  (mapv
   #(.getCanonicalPath (io/file %))
   (string/split (System/getProperty "java.class.path")
                 (java.util.regex.Pattern/compile (System/getProperty "path.separator")))))

(defn dynamic-classpath []
    (mapv
     #(.getCanonicalPath (io/file (.getFile %)))
     (mapcat
      #(seq (.getURLs %))
      (take-while some? (iterate #(.getParent %) (.getContextClassLoader (Thread/currentThread)))))))

#_((set (dynamic-classpath)) (.getCanonicalPath (io/file "src")))
#_(add-classpath! (.toURL (io/file "src")))

(defn dir-on-classpath? [dir]
  ((set (static-classpath)) (.getCanonicalPath (io/file dir))))

(defn dir-on-current-classpath? [dir]
  ((set (dynamic-classpath)) (.getCanonicalPath (io/file dir))))

(defn root-dynclass-loader []
  (last
   (take-while
    #(instance? clojure.lang.DynamicClassLoader %)
    (iterate #(.getParent ^java.lang.ClassLoader %) (.getContextClassLoader (Thread/currentThread))))))

(defn ensure-dynclass-loader! []
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (when-not (instance? clojure.lang.DynamicClassLoader cl)
      (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))))

(defn add-classpath! [url]
  (assert (instance? java.net.URL url))
  (ensure-dynclass-loader!)
  (when-not (dir-on-current-classpath? (.getFile url))
    (let [root-loader (root-dynclass-loader)]
      (.addURL ^clojure.lang.DynamicClassLoader root-loader url))))

;; this is a best guess for situations where the user doesn't
;; add the source directory to the classpath
(defn valid-source-path? [source-path]
  ;; TODO shouldn't contain preconfigured target directory
  (let [compiled-js (string/replace source-path #"\.clj[sc]$" ".js")]
    (and (not (.isFile (io/file compiled-js)))
         (not (string/starts-with? source-path "./out"))
         (not (string/starts-with? source-path "./target"))
         (not (string/starts-with? source-path "./resources"))
         (not (string/starts-with? source-path "./dev-resources"))
         (let [parts (path-parts source-path)
               fpart (second parts)]
           (and (not (#{"out" "resources" "target" "dev-resources"} fpart))
                (empty? (filter #{"public"} parts)))))))

(defn find-ns-source-in-local-dir [ns]
  (let [cljs-path (cljs.util/ns->relpath ns :cljs)
        cljc-path (cljs.util/ns->relpath ns :cljc)
        sep (System/getProperty "file.separator")]
    (->> (file-seq (io/file "."))
         (map str)
         (filter
          #(or (string/ends-with? % (str sep cljs-path))
               (string/ends-with? % (str sep cljc-path))))
         (filter valid-source-path?)
         (sort-by count)
         first)))

;; only called when ns isn't on classpath
(defn find-source-dir-for-cljs-ns [ns]
  (let [cljs-path (cljs.util/ns->relpath ns :cljs)
        cljc-path (cljs.util/ns->relpath ns :cljc)
        candidate (find-ns-source-in-local-dir ns)
        rel-source-path (if (string/ends-with? candidate "s")
                          cljs-path
                          cljc-path)
        candidate'
        (when candidate
          (let [path (string/replace candidate rel-source-path "")]
            (-> path
                (subs 0 (dec (count path)))
                (subs 2))))]
    (when (.isFile (io/file candidate' rel-source-path))
      candidate')))

#_(find-source-dir-for-cljs-ns 'exproj.core)

(defn ns->location [ns]
  (try (bapi/ns->location ns)
       (catch java.lang.IllegalArgumentException e
         (throw (ex-info
                 (str "ClojureScript Namespace " ns " was not found on the classpath.")
                 {:figwheel.main/error true})))))

(defn safe-ns->location [ns]
  (try (bapi/ns->location ns)
       (catch java.lang.IllegalArgumentException e
         nil)))

#_(ns->location 'asdf.asdf)
#_(safe-ns->location 'asdf.asdf)

#_(ns->location 'figwheel.main)
#_(safe-ns->location 'figwheel.main)

(defn ns-available? [ns]
  (or (safe-ns->location ns)
      (find-ns-source-in-local-dir ns)))

#_(ns-available? "exproj.core")

(defn source-file-types-in-dir [dir]
  (into
   #{}
   (map
    #(last (string/split % #"\."))
    (keep
     #(last (path-parts (str %)))
     (filter
      #(.isFile %)
      (file-seq (io/file dir)))))))
