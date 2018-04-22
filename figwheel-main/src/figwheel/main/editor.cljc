(ns figwheel.main.editor
  (:require
   [clojure.string :as string]
   [figwheel.repl]
   #?@(:cljs [[figwheel.tools.heads-up :as heads-up]
              [goog.log :as glog]]
       :clj [[clojure.java.io :as io]
             [clojure.java.shell :as shell]])))

#?(:cljs
   (do

;; TODO would rather have a global event to listen to here
(defmethod heads-up/heads-up-event-dispatch "file-selected" [dataset]
  (let [msg {:figwheel-event "file-selected"
             :file-name (.-fileName dataset)
             :file-line (.-fileLine dataset)
             :file-column (.-fileColumn dataset)}]
    (figwheel.repl/debug [:open-file-msg (pr-str msg)])
    (figwheel.repl/respond-to-connection msg)))

)

   :clj
   (do

(defn get-open-file-command [open-file-command {:keys [file-name file-line file-column]}]
  (when open-file-command
    (if (= open-file-command "emacsclient")
      (cond-> ["emacsclient" "-n"]
        (not (nil? file-line))
        (conj  (str "+" file-line
                    (when (not (nil? file-column))
                      (str ":" file-column))))
        true (conj file-name))
      [open-file-command file-name (str file-line) (str file-column)])))

(defn validate-file-selected-msg [{:keys [file-name file-line file-column] :as msg}]
  (and file-name (.exists (io/file file-name))
       (cond-> msg
         file-line   (assoc :file-line (java.lang.Integer/parseInt file-line))
         file-column (assoc :file-column (java.lang.Integer/parseInt file-column)))))

(defn exec-open-file-command [open-file-command msg]
  (when-let [msg (#'validate-file-selected-msg msg)]
    (if-let [command (get-open-file-command open-file-command msg)]
      (try
        (let [result (apply shell/sh command)]
          (if (zero? (:exit result))
            (println "Successful open file command: " (pr-str command))
            (println "Failed to call open file command: " (pr-str command)))
          (when-not (string/blank? (:out result))
            (println "OUT:")
            (println (:out result)))
          (when-not (string/blank? (:err result))
            (println "ERR:")
            (println (:err result)))
          (flush))
        (catch Exception e
          (println "Figwheel: there was a problem running the open file command - "
                   command)
          (println (.getMessage e))))
      (println "Figwheel: Can't open " (pr-str (vals (select-keys msg [:file-name :file-line :file-column])))
               "No :open-file-command supplied in the config."))))

(defn setup [open-file-command]
  (figwheel.repl/add-listener
   (fn [{:keys [response] :as msg}]
     (when (= "file-selected" (:figwheel-event response))
       (exec-open-file-command open-file-command response)))))

))
