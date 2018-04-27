(ns figwheel.main.logging
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [figwheel.core]
   [figwheel.main.ansi-party :as ap :refer [format-str]]
   [figwheel.main.util :as util]
   [figwheel.tools.exceptions :as fig-ex])
  (:import [java.util.logging
            Logger Level LogRecord
            Handler
            ConsoleHandler
            FileHandler
            Formatter]))

(defprotocol Log
  (fwlog! [logger level msg throwable])
  (fwsetlevel! [logger level]))

(def levels-map
  {:error  Level/SEVERE
   :fatal  Level/SEVERE
   :warn   Level/WARNING
   :info   Level/INFO
   :config Level/CONFIG
   :debug  Level/FINE
   :trace  Level/FINEST})

(extend java.util.logging.Logger
  Log
  {:fwlog!
   (fn [^java.util.logging.Logger logger level msg ^Throwable throwable]
     (let [^java.util.logging.Level lvl
           (get levels-map level Level/INFO)]
       (when (.isLoggable logger lvl)
         (if throwable
           (.log logger lvl msg throwable)
           (.log logger lvl msg)))))
   :fwsetlevel!
   (let [levels-map* (merge levels-map {:all Level/ALL :off Level/OFF})]
     (fn [^java.util.logging.Logger logger level]
       (some->>
        level
        (get levels-map*)
        (.setLevel logger))))})

(defn format-log-record [^LogRecord record]
  (let [lvl (.getLevel record)]
    (str "[Figwheel"
         (when-not (= lvl Level/INFO)
           (str ":" (.getName lvl)))
         "] "
         (.getMessage record) "\n"
         (when-let [m (.getThrown record)]
           (with-out-str
             (clojure.pprint/pprint (Throwable->map m)))))))

(def fig-formatter
  (proxy [Formatter] []
    (format [^LogRecord record]
      (format-log-record record))))

;; this supports rebel-readline better
;; TODO need to determine the best thing to do here
(defn writer-handler [print-writer-var]
  (proxy [Handler] []
    (close [])
    (flush []
      (binding [*out* @print-writer-var]
        (flush)))
    (publish [^LogRecord record]
      (binding [*out* @print-writer-var]
        (if-let [formatter (.getFormatter this)]
          (println (string/trim-newline (.format formatter record)))
          (println (string/trim-newline (.getMessage record))))))))

(defn default-logger
  ([n] (default-logger n (if (util/rebel-readline?)
                           (writer-handler #'*out*)
                           (ConsoleHandler.))))
  ([n handler]
   (let [l (Logger/getLogger n)]
     (when (empty? (.getHandlers l))
       (.addHandler l (doto handler
                        (.setFormatter fig-formatter)
                        ;; set level ALL here
                        (.setLevel java.util.logging.Level/ALL))))
     (.setUseParentHandlers l false)
     l)))

(def ^:dynamic *logger* (default-logger "figwheel.logg"))

(defn remove-handlers [logger]
  (doseq [h (.getHandlers logger)]
    (.removeHandler logger h)))

(defn switch-to-file-handler! [fname]
  (alter-var-root #'ap/*use-color* (fn [_] false))
  (remove-handlers *logger*)
  (.addHandler
   *logger*
   (doto (FileHandler. fname)
     (.setFormatter fig-formatter)
     (.setLevel java.util.logging.Level/ALL))))

(defn set-level [lvl-key]
  (fwsetlevel! *logger* lvl-key))
(d)
(defn info [& msg]
  (fwlog! *logger* :info (string/join " " msg) nil))

(defn warn [& msg]
  (fwlog! *logger* :warn (string/join " " msg) nil))

(defn error [msg & [e]]
  (fwlog! *logger* :error msg e))

(defn debug [& msg]
  (fwlog! *logger* :debug (string/join " " msg) nil))

(defn trace [& msg]
  (fwlog! *logger* :trace (string/join " " msg) nil))

(defn succeed [& msg]
  (info (format-str [:green (string/join " " msg)])))

(defn failure [& msg]
  (info (format-str [:red (string/join " " msg)])))

;; --------------------------------------------------------------------------------
;; Logging Syntax errors
;; --------------------------------------------------------------------------------

(defn exception-title [{:keys [tag warning-type]}]
  (if warning-type
    "Compile Warning"
    (condp = tag
      :clj/compiler-exception            "Couldn't load Clojure file"
      :cljs/analysis-error               "Could not Analyze"
      :tools.reader/eof-reader-exception "Could not Read"
      :tools.reader/reader-exception     "Could not Read"
      :cljs/general-compile-failure      "Could not Compile"
      "Compile Exception")))

(defn file-line-col [{:keys [line column file] :as ex}]
  [:file-line-col
   (when file (str file  "   "))
   (when line (str "line:" line "  "))
   (when column (str "column:" column))])

(defn exception-message [ex]
  [:cyan (exception-title ex) "   " (file-line-col ex)])

(defn exception-with-excerpt [e]
  (let [{:keys [file line] :as parsed-ex} (fig-ex/parse-exception e)
        file-excerpt (when (and file line (.exists (io/file file)))
                       (figwheel.core/file-excerpt (io/file file) (max 1 (- line 10)) 20))]
    (cond-> parsed-ex
      file-excerpt (assoc :file-excerpt file-excerpt))))

(defn except-data->format-lines-data [except-data]
  (let [data (figwheel.core/inline-message-display-data except-data)
        longest-number (->> data (keep second) (reduce max 0) str count)
        number-fn  #(format (str "  %" (when-not (zero? longest-number)
                                         longest-number)
                                 "s  ") %)]
    (apply vector :lines
           (map
            (fn [[k n s]]
              (condp = k
                :code-line [:yellow (number-fn n) s "\n"]
                :error-in-code [:line [:yellow (number-fn n) ] [:bright s] "\n"]
                :error-message [:yellow (number-fn "") (first (string/split s #"\^---")) "^---\n"]))
            data))))

(defn except-data->format-data [{:keys [message] :as except-data}]
  [:exception
   (when message [:yellow "  " message "\n"])
   "\n"
   (except-data->format-lines-data except-data)])

(defn format-exception-warning [data]
  [:lines (exception-message data) "\n\n"
   (except-data->format-data data)])

(defn syntax-exception [e]
  (-> (exception-with-excerpt e)
      format-exception-warning
      format-str
      info))

(defn cljs-syntax-warning [warning]
  (-> (figwheel.core/warning-info warning)
      format-exception-warning
      format-str
      info))

(defn cljs-syntax-warning-message [warning]
  (let [{:keys [message] :as data} (figwheel.core/warning-info warning)]
    (info
     (format-str
      [:lines
       (when message [:yellow message "  "])
       [:cyan (file-line-col data)]]))))
