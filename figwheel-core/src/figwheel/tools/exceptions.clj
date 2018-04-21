(ns figwheel.tools.exceptions
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]))

;; utils

(defn relativize-local [path]
  (.getPath
   (.relativize
    (.toURI (io/file (.getCanonicalPath (io/file "."))))
    ;; just in case we get a URL or some such let's change it to a string first
    (.toURI (io/file (str path))))))

;; compile time exceptions are syntax errors so we need to break them down into
;; message line column file

;; TODO handle spec errors

(defn cljs-analysis-ex? [tm]
  (some #{:cljs/analysis-error} (keep #(get-in %[:data :tag]) (:via tm))))

(defn reader-ex? [{:keys [data]}]
  (= :reader-exception (:type data)))

(defn eof-reader-ex? [{:keys [data] :as tm}]
  (and (reader-ex? tm) (= :eof (:ex-kind data))))

(defn cljs-failed-compiling? [tm]
  (some #(.startsWith % "failed compiling file:") (keep :message (:via tm))))

(defn clj-compiler-ex? [tm]
  (-> tm :via first :type (= clojure.lang.Compiler$CompilerException)))

(defn exception-type? [tm]
  (cond
    (cljs-analysis-ex? tm) :cljs/analysis-error
    (eof-reader-ex? tm)    :tools.reader/eof-reader-exception
    (reader-ex? tm)        :tools.reader/reader-exception
    (cljs-failed-compiling? tm) :cljs/general-compile-failure
    (clj-compiler-ex? tm)  :clj/compiler-exception
    :else nil))

(derive :tools.reader/eof-reader-exception :tools.reader/reader-exception)

(defmulti message exception-type?)

(defmethod message :default [tm] (:cause tm))

(defmethod message :tools.reader/reader-exception [tm]
  (or
   (some-> tm :cause (string/split #"\[line.*\]") second string/trim)
   (:cause tm)))

(defmulti blame-pos exception-type?)

(defmethod blame-pos :default [tm])

(defmethod blame-pos :cljs/analysis-error [tm]
  (select-keys
   (some->> tm :via reverse (filter #(get-in % [:data :line])) first :data)
   [:line :column]))

(defmethod blame-pos :tools.reader/eof-reader-exception [tm]
  (let [[line column]
        (some->> tm :cause (re-matches #".*line\s(\d*)\sand\scolumn\s(\d*).*")
                 rest)]
    (cond-> {}
      line   (assoc :line   (Integer/parseInt line))
      column (assoc :column (Integer/parseInt column)))))

(defmethod blame-pos :tools.reader/reader-exception [{:keys [data]}]
  (let [{:keys [line col]} data]
    (cond-> {}
      line (assoc :line   line)
      col  (assoc :column col))))

(defmethod blame-pos :clj/compiler-exception [tm]
  (let [[line column]
        (some->> tm :via first :message
                 (re-matches #"(?s).*\(.*\:(\d+)\:(\d+)\).*")
                 rest)]
    (cond-> {}
      line   (assoc :line   (Integer/parseInt line))
      column (assoc :column (Integer/parseInt column)))))

;; return relative path because it isn't lossy
(defmulti source-file exception-type?)

(defmethod source-file :default [tm])

(defn first-file-source [tm]
  (some->> tm :via (keep #(get-in % [:data :file])) first str))

(defmethod source-file :cljs/general-compile-failure [tm]
  (first-file-source tm))

(defmethod source-file :cljs/analysis-error [tm]
  (first-file-source tm))

(defmethod source-file :tools.reader/reader-exception [tm]
  (first-file-source tm))

(defn correct-file-path [file]
  (cond
    (nil? file) file
    (not (.exists (io/file file)))
    (if-let [f (io/resource file)]
      (relativize-local (.getPath f))
      file)
    :else (relativize-local file)))

(defmethod source-file :clj/compiler-exception [tm]
  (some->> tm :via first :message (re-matches #"(?s).*\(([^:]*)\:.*") second correct-file-path))

(defn data [tm]
  (or (:data tm) (->> tm :via reverse (keep :data) first)))

(defn ex-type [tm]
  (-> tm :via last :type))

(defn parse-exception [e]
  (let [tm     (if (instance? Throwable e) (Throwable->map e) e)
        tag    (exception-type? tm)
        msg    (message tm)
        pos    (blame-pos tm)
        file   (source-file tm)
        ex-typ (ex-type tm)
        data'  (data tm)]
    (cond->  (vary-meta {} assoc ::orig-throwable tm)
      tag    (assoc :tag tag)
      msg    (assoc :message msg)
      pos    (merge pos)
      file   (assoc :file file)
      ex-typ (assoc :type ex-typ)
      data'  (assoc :data data'))))
