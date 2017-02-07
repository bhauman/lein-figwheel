(ns figwheel-sidecar.cljs-utils.exception-parsing
  (:require
   [cljs.analyzer :as ana]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.stacktrace :as stack]
   [clojure.walk :refer [postwalk]]
   #_[clojure.pprint :refer [pprint]]
   [strictly-specking-standalone.ansi-util :refer [color-text]]
   #_[cljs.build.api :as bapi]
   [figwheel-sidecar.utils :as utils]))

(def serializable-datum? (some-fn map? list? vector? set? number? string? symbol? keyword?))

(defn serialize-filter [i]
  (cond
    ;; this is temporary while versions of clojurescript read-string can't
    ;; can't read namespaced maps like #:asdf {:hello 1 :there 2}
    ;; this is mainly a problem with wanting to ship
    ;; clojure.spec explain data to the figwheel client
    (keyword? i) (keyword (name i))
    (serializable-datum? i) i
    :else (str i)))

(def serialize-edn-for-js-env (partial postwalk serialize-filter))

(defn inspect-exception [ex]
  (->
   {:class (type ex)
    :message (.getMessage ex)
    :data (ex-data ex)
    :cause (when (.getCause ex) (inspect-exception (.getCause ex)))}
   ;; adding exception to meta data because I don't want it serialize over the wire
   ;; and its only here as a fall back
   ;; I also don't want it printed out during development
   (vary-meta assoc :orig-exception ex)))

(def flatten-exception #(take-while some? (iterate :cause %)))

(def exception-info? #(-> % :class (= clojure.lang.ExceptionInfo)))

(defn display-ex-data? [{:keys [cause]}]
  (when ((every-pred exception-info? :data) cause)
    {:display-ex-data (serialize-edn-for-js-env (:data cause))}))

(defn parse-loaded-clojure-exception [{:keys [exception-data] :as ex}]
  (if (-> exception-data :class (= clojure.lang.Compiler$CompilerException))
    (let [[_ file line column] (re-matches #"(?s).*\((.*)\:(\d+)\:(\d+)\)"
                                           (:message exception-data))]
      (cond-> (merge ex
                     (let [exceptions-to-try (cond->> [exception-data]
                                               file (cons (:cause exception-data)))]
                       {:failed-loading-clj-file true
                        :message (first (keep :message exceptions-to-try))
                        :class   (first (keep :class   exceptions-to-try))})
                     (display-ex-data? exception-data))
        file   (assoc :file file)
        line   (assoc :line (Integer/parseInt line))
        column (assoc :column (Integer/parseInt column))))
    ex))

(defn parse-failed-compile [{:keys [exception-data] :as ex}]
  (if (and
       (exception-info? exception-data)
       (:message exception-data)
       (->> exception-data :message (re-matches #"failed compiling.*")))
    (assoc ex
             :failed-compiling true
             :message (:message exception-data)
             :file (get-in exception-data [:data :file]))
    ex))

(defn some-exception-info [pred-fn exception-data]
  (->> (flatten-exception exception-data)
       (filter (every-pred exception-info? pred-fn))
       first))

(defn parse-analysis-error [{:keys [exception-data] :as ex}]
  (if-let [analysis-exception
           (some-exception-info #(= (-> % :data :tag) :cljs/analysis-error) exception-data)]
    (merge {:analysis-exception true
            :class      (get-in analysis-exception [:cause :class])}
           (select-keys (:data analysis-exception) [:file :line :column :root-source-info])
           ex
           (display-ex-data? analysis-exception)
           {:message (first (keep :message ((juxt :cause identity) analysis-exception)))})
    ex))

(defn parse-reader-error [{:keys [exception-data] :as ex}]
  (if-let [reader-exception
           (some-exception-info #(= (-> % :data :type) :reader-exception) exception-data)]
    (merge {:reader-exception true}
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
  (let [exception-data (flatten-exception exception-data)
        {:keys [file line]} (apply merge (keep :data exception-data))]
    (cond-> ex
        (-> ex :file nil?)    (assoc :file file)
        (-> ex :line nil?)    (assoc :line line)
        (-> ex :message nil?) (assoc :message (last (keep :message exception-data))))))

(defn remove-file-from-message [{:keys [message file] :as ex}]
  (if (and file (re-matches #".*in file.*" message))
    (assoc ex :message (first (string/split message #"in file")) )
    ex))

;;; above here is exception specific

;;; in context display

(defn breaker-line [previous-line message column]
  (let [previous-line-start-pos (count (take-while #(= % \space) previous-line))]
    (cond
      column  (str (apply str (repeat (dec column)
                                      \space)) "^--- "
                   (if message message ""))
      :else (str (apply str (repeat previous-line-start-pos \space)) (if message message "^^^^ error originates on line above ^^^^")))))

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

;; more interesting handling of exceptions
(defn blank-line-column-prefix [line column]
  (apply str
         (concat (repeat (dec line) "\n")
                 (repeat (dec column) " "))))

(defn repl-prompt-indent [environment current-ns]
  (if (= environment :repl)
    (let [current-ns ((fnil name "") current-ns)]
      (+ 2 (count current-ns)))
    0))

;; if we are in a repl environment
;; we need to account for the prompt
(defn extract-and-format-source
  [{:keys [source line column]} environment current-ns]
  (let [res (str (blank-line-column-prefix line column) source)]
    (if (= :repl environment)
      (str (apply str
                  (repeat
                   (repl-prompt-indent environment current-ns)
                   " ")) res)
      res)))

(defn fetch-code-lines [{:keys [file root-source-info source-form environment current-ns] :as ex}]
  (let [source-data (meta (or source-form (-> root-source-info :source-form)))]
    (cond
      (and source-data (not (string/blank? (:source source-data))))
      (string/split (extract-and-format-source
                     source-data environment current-ns)
                    #"\n")
      (and file (.exists (io/file file)))
      (doall (line-seq (io/reader (io/file file))))
      :else nil)))

#_(fetch-code-lines {:file "<cljs repl>"
                   :environment :repl
                   :current-ns 'cljs.user
                   :root-source-info {:source-form (vary-meta '(do )
                                                              merge {:source "hello"
                                                                     :line 1 :column 1})}})

(defn display-error-in-context [{:keys [message line file column root-source-info environment current-ns]
                                 :as ex}
                                context-amount]
  (if-let [lines (not-empty (fetch-code-lines ex))]
    (if (and line (<= 0 line (count lines)))
      (->>
       (concat (map-indexed
                #(vector :code-line (inc %1) %2)
                (take (dec line) lines))
               [[:error-in-code line (nth lines (dec line))]]
               [[:error-message nil
                 (breaker-line (nth lines (dec line))
                               (when-not (= environment :repl) message)
                               (if (= line 1)
                                 (+ (repl-prompt-indent
                                     environment
                                     current-ns)
                                    column)
                                 column))]]
               (map-indexed
                #(vector :code-line (+ line (inc %1)) %2)
                (drop line lines)))
       (take-range (- line context-amount) (+ line context-amount))
       trim-blank
       (assoc ex :error-inline))
      ex)
    ex))

(defn relativize-file-to-project-root [{:keys [file] :as ex}]
  (if file
    (update-in ex [:file] utils/relativize-local)
    ex))

;; parse exceptions

(defn parse-inspected-exception
  ([inspected-exception] (parse-inspected-exception inspected-exception nil))
  ([inspected-exception opts]
   (-> {:exception-data inspected-exception}
       (merge opts)
       parse-loaded-clojure-exception
       parse-failed-compile
       parse-analysis-error
       parse-reader-error
       patch-eof-reader-exception
       remove-file-from-message
       relativize-file-to-project-root
       (display-error-in-context (if (= (:environment opts) :repl) 15 3))
       ;; remove data before shipping over the wire
       (update-in [:exception-data]
                  (fn remove-data [d]
                    (cond-> d
                      (:cause d) (update-in [:cause] remove-data)
                      (:data d)  (dissoc :data)))))))

(comment
  (parse-inspected-exception (example-ex :analysis))
  (parse-inspected-exception (example-ex :analysis2))
  (parse-inspected-exception (example-ex :analysis3))
  (parse-inspected-exception (example-ex :reader))
  (parse-inspected-exception (example-ex :plain-exception))
  (parse-inspected-exception (example-ex :repl-based-exception))
  )

(defn parse-exception
  ([exception] (parse-exception exception nil))
  ([exception opts]
   (-> exception
       inspect-exception
       (parse-inspected-exception opts))))

;; parse warnings

;;; --- WARNINGS

(defn extract-warning-data [warning-type env extra]
  (when (warning-type cljs.analyzer/*cljs-warnings*)
    (when-let [s (cljs.analyzer/error-message warning-type extra)]
      {:line   (:line env)
       :column (:column env)
       :ns     (-> env :ns :name)
       :file (if (= (-> env :ns :name) 'cljs.core)
               "cljs/core.cljs"
               ana/*cljs-file*)
       :message s
       :extra   extra})))

(defn parse-warning [{:keys [message warning-type
                             line column ns file message extra] :as warning}]
  (-> warning
      relativize-file-to-project-root
      (display-error-in-context 5)))

;; display

(defn get-class-name [x]
  (try
    (.getName x)
    (catch Exception e x)))

(defn exception-data->display-data [{:keys [failed-compiling reader-exception analysis-exception
                                            failed-loading-clj-file
                                            class file line column message error-inline environment current-ns] :as exception}]
  (let [direct-form (#{"<cljs repl>" "NO_SOURCE_FILE"} file)
        file         (if direct-form "<cljs form>" file)
        last-message (cond
                       direct-form nil
                       file (str "Please see " file)
                       :else nil)]
    {:type ::exception
     :error-type (cond
                   failed-loading-clj-file "Clojure File Load Error"
                   analysis-exception "Analysis Error"
                   reader-exception   "Reader Error"
                   failed-compiling   "Compiler Error"
                   :else "Exception")
     :head (cond
             failed-loading-clj-file "Couldn't load Clojure file"
             analysis-exception "Could not Analyze"
             reader-exception   "Could not Read"
             failed-compiling   "Could not Compile"
             :else "Exception")
     :sub-head file
     :messages (map
                #(update-in % [:class] get-class-name)
                (if message
                 [{:class class :message message}]
                 (flatten-exception (:exception-data exception))))
     :stack-trace (when-not (or failed-compiling reader-exception analysis-exception)
                    (when-let [e (-> exception :exception-data flatten-exception last meta :orig-exception)]
                      (with-out-str (stack/print-cause-trace e))))
     :error-inline error-inline
     :last-message last-message
     :file file
     :line line
     :column column
     :environment environment
     :current-ns current-ns}))

(defn warning-data->display-data [{:keys [file line column message
                                          error-inline ns environment current-ns] :as exception}]
  (let [direct-form (#{"<cljs repl>" "NO_SOURCE_FILE"} file)
        file         (if direct-form "<cljs form>" file)
        last-message (cond
                       direct-form nil
                       file (str "Please see " file)
                       :else nil)]
    {:type ::warning
     :error-type "Compiler Warning"
     :head "Compiler Warning on "
     :sub-head file
     :messages [{:message message}]
     :error-inline error-inline
     :last-message last-message
     :environment environment
     :current-ns current-ns
     :file file
     :line line
     :column column}))

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
    (format-code-line :cyan :cyan line-number line)))

(defn pad-line-numbers [inline-error]
  (let [max-line-number-length (count (str (reduce max (keep second
                                                             inline-error))))]
    (map #(update-in % [1]
                     (partial left-pad-string max-line-number-length)) inline-error)))

(defn min-lead-whitespace [lines]
  (reduce min (map (fn [[_ _ line]] (count (take-while #(re-matches #"\s" (str %)) line))) lines)))

(defn truncate-leading-space [lines]
  (let [subtract-space (min-lead-whitespace lines)]
    (mapv (fn [[a b line]]
            [a b (apply str (drop subtract-space line))])
          lines)))

(defn format-error-inline [context-code-lines]
  (let [lines (pad-line-numbers context-code-lines)
        lines (truncate-leading-space lines)]
    (string/join "\n" (map format-error-line lines))))

;; needs to know the length of the current namespace
(defn repl-pointer-line [current-ns line column]
  (when (and column current-ns)
    (str (apply str (repeat (+ column
                                 (if (> line 1)
                                   0
                                   (inc (count (name current-ns)))))
                            \space))
         (color-text "^" :red) " \n")))

#_(repl-pointer-line 'cljs.user 3 10)

(defn formatted-exception-display-str [{:keys [current-ns environment error-type head sub-head last-message messages
                                               line column error-inline stack-trace] :as display-data}]
  (str
   (when (and (= :repl environment) (empty? error-inline))
     (repl-pointer-line current-ns line column))
   (color-text (str "----  " head "  " sub-head "  "
                    (when line (str " line:" line " "))
                    (when column (str " column:" column "  "))
                    "----")
               :cyan)
   "\n\n"
   (let [max-len (reduce max (map (comp count :class) messages))]
     (str
      (if (= 1 (count messages))
        (str "  "
             (color-text (-> messages first :message)
                         (if (= environment :repl)
                           :yellow
                           :bold)))
        (string/join "\n"
                     (map
                      (fn [{:keys [class message]}]
                        (str "  " (when class (str (left-pad-string max-len class) " : "))
                             (color-text message :bold)))
                      messages)))
          "\n\n"))
   (when (pos? (count error-inline))
     (str  (format-error-inline error-inline)
          "\n\n"))
   (color-text (str "----  " (if stack-trace
                               "Exception Stack Trace"
                               (str error-type
                                    (when last-message
                                      (str " : " last-message))))
                    "  ----")
               :cyan)
   (when stack-trace
     (str "\n\n" stack-trace))))

(defn print-exception
  ([exception] (print-exception exception nil))
  ([exception opts]
   (-> exception
       (parse-exception opts)
       exception-data->display-data
       formatted-exception-display-str
       println)
   (flush)))

(defn format-warning [warning]
  (-> warning
      parse-warning
      warning-data->display-data
      formatted-exception-display-str))

#_(-> (example-ex :analysis-no-message) #_(:reader example-ex)
      parse-inspected-exception
      exception-data->display-data
      formatted-exception-display-str)

;; this is only for development

#_(def example-ex
  {:analysis {:class clojure.lang.ExceptionInfo,
                  :message "failed compiling file:test.cljs",
                  :data {:file "test.cljs"},
                  :cause
                  {:class clojure.lang.ExceptionInfo,
                   :message "Wrong number of args (0) passed to: core/defn--32200 at line 6 test.cljs",
                   :data {:file "test.cljs", :line 6, :column 4, :tag :cljs/analysis-error},
                   :cause {:class clojure.lang.ArityException, :message "Wrong number of args (0) passed to: core/defn--32200", :data nil, :cause nil}}}
   :analysis2 {:class clojure.lang.ExceptionInfo,
                   :message "failed compiling file:test.cljs",
                   :data {:file "test.cljs"},
                   :cause
                   {:class clojure.lang.ExceptionInfo,
                    :message "Parameter declaration \"a\" should be a vector at line 6 test.cljs",
                    :data {:file "test.cljs", :line 6, :column 4, :tag :cljs/analysis-error},
                    :cause {:class java.lang.IllegalArgumentException, :message "Parameter declaration \"a\" should be a vector", :data nil, :cause nil}}}
   :analysis3 {:class clojure.lang.ExceptionInfo,
               :message "failed compiling file:test.cljs",
               :data {:file "test.cljs"},
               :cause
               {:class clojure.lang.ExceptionInfo,
                :message "Invalid :refer, var var cljs.core.async/yep does not exist in file test.cljs",
                :data {:tag :cljs/analysis-error},
                :cause nil}}
   :analysis-no-message {:class clojure.lang.ExceptionInfo,
                         :data {:file "test.cljs"},
                         :cause
                         {:class clojure.lang.ExceptionInfo,
                          :data {:tag :cljs/analysis-error},
                          :cause nil}}
   :reader {:class clojure.lang.ExceptionInfo,
            :message "failed compiling file:test.cljs",
            :data {:file "test.cljs"},
            :cause
            {:class clojure.lang.ExceptionInfo,
             :message "EOF while reading, starting at line 3 and column 4",
             :data {:type :reader-exception, :line 10, :column 1, :file "/Users/bhauman/workspace/lein-figwheel/example/test.cljs"},
             :cause nil}}
   :plain-exception (let [e (try (throw (Exception. "HEEEY"))
                                                    (catch Throwable e
                                                      e))]
                      (inspect-exception e))
   :repl-based-exception {:class clojure.lang.ExceptionInfo,
                          :message "Wrong number of args (0) passed to: core/defn--32200 at line 1 <cljs repl>",
                          :data {:file "<cljs repl>", :line 1, :column 1, :tag :cljs/analysis-error},
                          :cause {:class clojure.lang.ArityException,
                                  :message "Wrong number of args (0) passed to: core/defn--32200", :data nil, :cause nil}}

   })


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

