(ns figwheel.main.test.utils
  (:require
   [clojure.java.io :as io]
   [figwheel.main.logging :as log]))

(defn rename-file [filename new-filename]
  (let [f (io/file filename)]
    (when (.isFile f)
      (.renameTo f (io/file new-filename))
      (.delete f))))

(defn backup-file [filename]
  (rename-file filename (str filename "._backup")))

(defn restore-file [filename]
  (if (.isFile (io/file (str filename "._backup")))
    (rename-file (str filename "._backup") filename)
    (when (.isFile (io/file filename))
      (.delete (io/file filename)))))

(defn place-files [files-map]
  (doseq [[filename content] files-map]
    (backup-file filename)
    (when-not (= ":delete" (clojure.string/trim content))
      (spit (io/file filename) content))))

(defn restore-files [files-map]
  (doseq [[filename content] files-map]
    (restore-file filename)))

(defn with-files* [files-map thunk]
  (place-files files-map)
  (try
    (thunk)
    (finally
      (restore-files files-map))))

(defn with-edn-files* [files-map thunk]
  (with-files* (into {} (map (fn [[k v]] [(name k)
                                          (with-out-str (clojure.pprint/pprint v))])
                             files-map))
    thunk))

(defmacro with-edn-files
  "Make a temporary set of files that can shadow existing files

  specifying :delete as the content of a file will back it up only and
  then restore it after completion.

Example:
  (with-edn-files
    {:hithere {:some-edn 1}
     :figwheel-main.edn :delete}
    (slurp \"hithere\"))
  => \"{:some-edn 1}\n\""
  [files-map & body]
  `(with-edn-files*
     ~files-map
     (fn [] ~@body)))

#_(with-edn-files
  {"hithere" {:hi :there}}
    (slurp "hithere"))

(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls."
  {:added "1.0"}
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))

(defn logging-fixture [f]
  (with-redefs [log/*logger* (log/default-logger "figmain.test.logger" (log/writer-handler))]
    (f)))
