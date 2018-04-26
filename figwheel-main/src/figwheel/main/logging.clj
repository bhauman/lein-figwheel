(ns figwheel.main.logging
  (:require
   [clojure.string :as string]
   [figwheel.main.ansi-party :refer [format-str]])
  (:import [java.util.logging Logger Level ConsoleHandler Formatter]))

(defprotocol Log
  (fwlog! [logger level msg throwable]))

(extend java.util.logging.Logger
  Log
  {:fwlog!
   (fn [^java.util.logging.Logger logger level msg ^Throwable throwable]
     (let [^java.util.logging.Level lvl
           (get {:error  Level/SEVERE
                 :fatal  Level/SEVERE
                 :warn   Level/WARNING
                 :info   Level/INFO
                 :config Level/CONFIG
                 :debug  Level/FINE
                 :trace  Level/FINEST} level Level/INFO)]
       (when (.isLoggable logger lvl)
         (if throwable
           (.log logger lvl msg throwable)
           (.log logger lvl msg)))))})

(def fig-formatter (proxy [Formatter] []
                     (format [record]
                       (let [lvl (.getLevel record)]
                         (str "[Figwheel"
                              (when-not (= lvl Level/INFO)
                                (str ":" (.getName lvl)))
                              "] "
                              (.getMessage record) "\n"
                              (when-let [m (.getThrown record)]
                                (with-out-str
                                  (clojure.pprint/pprint (Throwable->map m)))))))))

(defn default-logger
  ([n] (default-logger n (ConsoleHandler.)))
  ([n handler]
   (let [l (Logger/getLogger n)]
     (when (empty? (.getHandlers l))
       (.addHandler l (doto handler
                        (.setFormatter fig-formatter)
                        (.setLevel java.util.logging.Level/ALL))))
     (.setUseParentHandlers l false)
     l)))

(def ^:dynamic *logger* (default-logger "figwheel.loggggg"))

#_(.setLevel *logger* java.util.logging.Level/INFO)

(.getLevel *logger*)

(defn info [& msg]
  (fwlog! *logger* :info (string/join " " msg) nil))

(defn warn [& msg]
  (fwlog! *logger* :warn (string/join " " msg) nil))

(defn error [msg e]
  (fwlog! *logger* :error msg e))

(defn debug [& msg]
  (fwlog! *logger* :debug (string/join " " msg) nil))

(defn trace [& msg]
  (fwlog! *logger* :trace (string/join " " msg) nil))

(defn succeed [& msg]
  (info (format-str [:green (string/join " " msg)])))

(defn failure [& msg]
  (info (format-str [:red (string/join " " msg)])))

#_ (error "hey" (ex-info "hey" {}))

#_ (debug "hey")

#_ (info "hey")
