(ns figwheel-sidecar.cljs-utils.exception-parsing
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.stacktrace :as stack]
   [figwheel-sidecar.config-check.ansi :refer [with-color color-text]]
   [cljs.build.api :as bapi]))

;; more interesting handling of exceptions

(defn relativize-local [path]
  (.getPath
   (.relativize
    (.toURI (io/file (.getCanonicalPath (io/file "."))))
    (.toURI (io/file path)))))

(defn data-serialize [o]
  (cond
    (or (number? o)
        (symbol? o)
        (keyword? o)) o
    (= (type o) java.io.File)
    (relativize-local o)
    :else (str o)))

(defn inspect-exception [ex]
  {:class (type ex)
   :message (.getMessage ex)
   :data (when-let [data (ex-data ex)]
           (->> data
                (map #(vector (first %) (data-serialize (second %))))
                (into {})))  
   :cause (when (.getCause ex) (inspect-exception (.getCause ex)))})

(defn flatten-exception [ex]
  (->> ex
       (iterate :cause)
       (take-while (comp not nil?))))

(defn exception-info? [ex] (= (:class ex) clojure.lang.ExceptionInfo))

(defn parse-failed-compile [{:keys [exception-data] :as ex}]
  (let [exception (first exception-data)]
    (if (and
         (exception-info? exception)
         (->> exception
             :message
             (re-matches #"failed compiling.*")))
      (assoc ex
             :failed-compiling true
             :message (:message exception)
             :file (get-in exception [:data :file]))
      ex)))

(defn parse-analysis-error [{:keys [exception-data] :as ex}]
  (if-let [analysis-exception
           (first
            (filter (fn [{:keys [data] :as exc}]
                      (when (and (exception-info? exc) data)
                        (= (:tag data) :cljs/analysis-error)))
                    exception-data))]
    (merge {:analysis-exception analysis-exception
            :class   (get-in analysis-exception [:cause :class])}
           (select-keys (:data analysis-exception) [:file :line :column])
           ex
           {:message (or (get-in analysis-exception [:cause :message])
                         (:message analysis-exception))})
    ex))

(defn parse-reader-error [{:keys [exception-data] :as ex}]
  (if-let [reader-exception
           (first
            (filter (fn [{:keys [data] :as exc}]
                      (when (and (exception-info? exc) data)
                        (= (:type data) :reader-exception)))
                    exception-data))]
    (merge {:reader-exception reader-exception}
           (select-keys (:data reader-exception) [:file :line :column])
           ex
           {:message (:message reader-exception)})
    ex))

;; we need to patch the line, column numbers for EOF Reader Exceptions
(defn patch-eof-reader-exception [{:keys [reader-exception message] :as ex}]
  (if (and reader-exception (re-matches #"EOF while reading, starting.*" message))
    (when-let [[_ line column] (re-matches #".*line\s(\d*)\sand\scolumn\s(\d*).*" message)]
      (assoc ex
             :line (java.lang.Integer/parseInt line)
             :column (java.lang.Integer/parseInt column)))
    ex))

;; last resort if no line or file data available in exception
(defn ensure-file-line [{:keys [exception-data] :as ex}]
  (let [{:keys [file line]} (apply merge (keep :data exception-data))]
    (cond->> ex
        (-> :file nil?) (assoc ex :file file)
        (-> :line nil?) (assoc ex :line line)
        (-> :message nil?) (assoc ex :message (last (keep :message exception-data))))))

(defn remove-file-from-message [{:keys [message file] :as ex}]
  (if (and file (re-matches #".*in file.*" message))
    (assoc ex :message (first (string/split message #"in file")) )
    ex))

(defn breaker-message [message]
  (if message message "error starts here"))

(defn breaker-line [previous-line message column]
  (let [previous-line-start-pos (count (take-while #(= % \space) previous-line))]
    (cond
      column  (str (apply str (repeat (dec column)
                                      \space)) "^--- " (if message message "error starts here"))
      :else (str (apply str (repeat previous-line-start-pos \space)) (if message message "^^^^ error originates on line above ^^^^")))))

;;; in context display

(defn trim-blank* [lines]
  (drop-while #(string/blank? (nth % 2)) lines))

(defn trim-blank [lines]
  (-> lines
      reverse
      trim-blank*
      reverse
      trim-blank*))

(defn take-range [start end lines]
  (map second
       (filter
        #(<= start (first %) end)
        (map-indexed vector lines))))

(defn display-error-in-context [{:keys [message line file column] :as ex}
                                context-amount]
  (let [lines (doall (line-seq (io/reader file)))]
    (if (and line (<= 0 line (count lines)))
      (->>
       (concat (map-indexed
                #(vector :code-line (inc %1) %2)
                (take (dec line) lines))
               [[:error-in-code line (nth lines (dec line))]]
               [[:error-message nil (breaker-line (nth lines (dec line))
                                              message
                                              column)]]
               (map-indexed
                #(vector :code-line (+ line (inc %1)) %2)
                (drop line lines)))
       (take-range (- line context-amount) (+ line context-amount))
       trim-blank
       (assoc ex :error-inline))
      ex)))

(defn parse-inspected-exception [inspected-exception]
  (-> {:exception-data (flatten inspected-exception)}
      parse-failed-compile
      parse-analysis-error
      parse-reader-error
      patch-eof-reader-exception
      remove-file-from-message
      (display-error-in-context 3)))

(defn parse-exception [exception]
  (-> exception
      inspect-exception
      parse-inspected-exception))

;; display

(defn get-class-name [x]
  (try
    (.getName x)
    (catch Exception e x)))

(defn exception-data->display-data [{:keys [failed-compiling reader-exception analysis-exception
                                            class file line column message error-inline] :as exception}]
  (let [last-message (cond
                       (and file line column)
                       (str "Please see line " line ", column " column " of file " file )
                       (and file line)
                       (str "Please see line " line " of file " file )
                       file (str "Please see " file)
                       :else nil)]
    {:error-type (cond
                   analysis-exception "Analysis"
                   reader-exception   "Reader"
                   failed-compiling   "Compiler"
                   :else "Compiler")
     :head (cond
                analysis-exception "Could not Analyze"
                reader-exception   "Could not Read"
                failed-compiling   "Could not Compile"
                :else "Compile Exception")
     :sub-head file
     :messages (map
                #(update-in % [:class] get-class-name)
                (if message
                 [{:class class :message message}]
                 (:exception-data exception)))
     :error-inline error-inline
     :last-message last-message
     :file file
     :line line
     :column column
     }))

(defn left-pad-string [n s]
  (let [len (count ((fnil str "") s))]
    (-> (if (< len n)
          (apply str (repeat (- n len) " "))
          "")
        (str s))))

(defn format-code-line [number-color body-color line-number line]
  (str "  " (color-text line-number number-color)
       "  " (color-text line body-color)))

(defn format-error-line [[code line-number line]]
  (condp = code
    :error-in-code (format-code-line :yellow :bold line-number line)
    :error-message (format-code-line :none :yellow line-number line)
    (format-code-line :none :none line-number line)))

(defn pad-line-numbers [inline-error]
  (let [max-line-number-length (count (str (reduce max (keep second
                                                             inline-error))))]
    (map #(update-in % [1]
                     (partial left-pad-string max-line-number-length)) inline-error)))

(defn format-error-inline [context-code-lines]
  (let [lines (pad-line-numbers context-code-lines)]
    (string/join "\n" (map format-error-line lines))))

(defn formatted-exception-display-str [{:keys [error-type head sub-head last-message messages line column error-inline] :as display-data}]
  (str
   (color-text (str "----  " head "  " sub-head "  "
                    (when line (str " line:" line " "))
                    (when column (str " column:" column "  "))
                    "----")
               :cyan)
   "\n\n"
   (let [max-len (reduce max (map (comp count :class) messages))]
     (str (string/join "\n"
                       (map
                        (fn [{:keys [class message]}]
                          (str "  " (when class (str (left-pad-string max-len class) " : "))
                               (color-text message :bold)))
                        messages))
          "\n\n"))
   (when (pos? (count error-inline))
     (str  (format-error-inline error-inline)
          "\n\n"))
   (color-text (str "---- " error-type " Error: " last-message "  ----")
               :cyan)))

(defn print-exception [exception]
  
  (try
    #_(int "asd")
    (-> exception
        parse-exception
        exception-data->display-data
        formatted-exception-display-str
        println)
    ;; print something if there is an error in the code above;
    ;; this is a signal that something is wrong with the code
    ;; this is necessary because these exceptions are getting eaten??? TODO
    (catch Throwable e
      (stack/print-cause-trace exception))))




#_(try
    (bapi/build "test.cljs" {:output-to "target/figwheel-dev-temp/test.js"
                             :output-dir "target/figwheel-dev-temp"
                             })
    (catch Throwable e
      (inspect-exception e)
      #_(print-exception e)
      #_(->> 
           exception-data->display-data
           formatted-exception-display-str
           #_println
          )
      #_(println ())
    #_(ex-data e)))
   
(comment

  (def analysis-ex {:class clojure.lang.ExceptionInfo,
                    :message "failed compiling file:test.cljs",
                    :data {:file "test.cljs"},
                    :cause
                    {:class clojure.lang.ExceptionInfo,
                     :message "Wrong number of args (0) passed to: core/defn--32200 at line 6 test.cljs",
                     :data {:file "test.cljs", :line 6, :column 4, :tag :cljs/analysis-error},
                     :cause {:class clojure.lang.ArityException, :message "Wrong number of args (0) passed to: core/defn--32200", :data nil, :cause nil}}})
  (def analysis-ex2 {:class clojure.lang.ExceptionInfo,
                     :message "failed compiling file:test.cljs",
                     :data {:file "test.cljs"},
                     :cause
                     {:class clojure.lang.ExceptionInfo,
                      :message "Parameter declaration \"a\" should be a vector at line 6 test.cljs",
                      :data {:file "test.cljs", :line 6, :column 4, :tag :cljs/analysis-error},
                      :cause {:class java.lang.IllegalArgumentException, :message "Parameter declaration \"a\" should be a vector", :data nil, :cause nil}}})

  (def analysis-ex3 {:class clojure.lang.ExceptionInfo,
                     :message "failed compiling file:test.cljs",
                     :data {:file "test.cljs"},
                     :cause
                     {:class clojure.lang.ExceptionInfo,
                      :message "Invalid :refer, var var cljs.core.async/yep does not exist in file test.cljs",
                      :data {:tag :cljs/analysis-error},
                      :cause nil}})

  (def reader-ex {:class clojure.lang.ExceptionInfo,
                  :message "failed compiling file:test.cljs",
                  :data {:file "test.cljs"},
                  :cause
                  {:class clojure.lang.ExceptionInfo,
                   :message "EOF while reading, starting at line 3 and column 4",
                   :data {:type :reader-exception, :line 10, :column 1, :file "/Users/bhauman/workspace/lein-figwheel/example/test.cljs"},
                   :cause nil}})
  
  
  )

