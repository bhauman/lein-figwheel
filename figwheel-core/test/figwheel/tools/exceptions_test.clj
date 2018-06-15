(ns figwheel.tools.exceptions-test
  (:require
   [cljs.build.api :as bapi]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [figwheel.tools.exceptions :refer :all]
   [clojure.test :refer [deftest is testing]]))

#_(remove-ns 'figwheel.tools.exceptions-test)

;; -----------------------------
;; helpers to capture exceptions
;; -----------------------------

(defn example-test-file! [p code]
  (io/make-parents (io/file p))
  (spit p (str (prn-str '(ns example.except)) code)))

(defn fetch-exception [code]
  (let [p "dev/example/except.cljs"]
    (example-test-file! p code)
    (.delete (io/file "target/test_out/example/except.js"))
    (try
      (bapi/build "dev" {:output-dir "target/test_out" :main "example.except" :output-to "target/test_out/main.js"})
      (catch Throwable e
        (Throwable->map e)))))

(defn fetch-clj-exception [code]
  (let [p "dev/example/except.clj"]
    (example-test-file! p code)
    (try
      (load-file p)
      (catch Throwable e
        (Throwable->map e)))))

(defn anonymise-ex
  "Remove system specific information from exceptions
  so that tests produce the same results on different
  systems."
  [ex-map]
  (update ex-map :data dissoc :file))

(deftest exception-parsing-test
  (is (= {:tag :cljs/analysis-error,
          :line 2,
          :column 1,
          :file "dev/example/except.cljs",
          :type 'clojure.lang.ArityException,
          :data
          {:file "dev/example/except.cljs",
           :line 2,
           :column 1,
           :tag :cljs/analysis-error}}
         (dissoc (parse-exception (fetch-exception "(defn)")) :message)))

  (is (= "Wrong number of args (0) passed to"
         (some-> (fetch-exception "(defn)")
                 parse-exception
                 :message
                 (string/split #":")
                 first)))

  (is (= {:tag :tools.reader/eof-reader-exception,
          :message
          "Unexpected EOF while reading item 1 of list, starting at line 2 and column 1.",
          :line 2,
          :column 1,
          :file "dev/example/except.cljs",
          :type 'clojure.lang.ExceptionInfo,
          :data
          {:type :reader-exception,
           :ex-kind :eof,
           :line 2,
           :col 7}}
         (anonymise-ex (parse-exception (fetch-exception "(defn ")))))

  (is (= {:tag :tools.reader/reader-exception,
          :message "Unmatched delimiter ).",
          :line 2,
          :column 2,
          :file "dev/example/except.cljs",
          :type 'clojure.lang.ExceptionInfo,
          :data
          {:type :reader-exception,
           :ex-kind :reader-error,
           :file
           (.getCanonicalPath (io/file "dev/example/except.cljs",))
           :line 2,
           :col 2}}
         (parse-exception (fetch-exception "))"))))

  (is (= {:tag :tools.reader/reader-exception,
          :message "No reader function for tag asdf.",
          :line 2,
          :column 6,
          :file "dev/example/except.cljs",
          :type 'clojure.lang.ExceptionInfo,
          :data
          {:type :reader-exception,
           :ex-kind :reader-error,
           :file (.getCanonicalPath (io/file "dev/example/except.cljs",))
           :line 2,
           :col 6}}
         (parse-exception (fetch-exception "#asdf {}"))))



  (is (= {:tag :clj/compiler-exception,
          :message "No reader function for tag asdf",
          :line 2,
          :column 9,
          :file "dev/example/except.clj",
          :type 'java.lang.RuntimeException
          }
         (parse-exception (fetch-clj-exception "#asdf {}"))))

  (is (= {:tag :clj/compiler-exception,
          :message "EOF while reading, starting at line 2",
          :line 2,
          :column 1,
          :file "dev/example/except.clj",
          :type 'java.lang.RuntimeException}
       (parse-exception (fetch-clj-exception "      (defn"))))


  )


;; TODO work on spec exceptions
#_(def clj-version
  (read-string (string/join "."
                            (take 2 (string/split (clojure-version) #"\.")))))

#_(when (>= clj-version 1.9)

  #_(parse-exception (fetch-clj-exception "(defn)"))

  )
