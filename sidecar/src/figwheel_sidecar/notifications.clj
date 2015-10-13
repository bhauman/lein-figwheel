(ns figwheel-sidecar.notifications
  (:require
   [figwheel-sidecar.core :as fig]
   [cljs.env :as env]
   [cljs.analyzer :as ana]
   [clojure.stacktrace :as stack]))

;; change notifications

(defn notify-change-helper [{:keys [figwheel-server build-config additional-changed-ns]} files]
  (let [changed-ns (set (concat
                         (keep fig/get-ns-from-source-file-path
                              (filter #(or (.endsWith % ".cljs")
                                           (.endsWith % ".cljc")) files))
                         additional-changed-ns))]
    (when-not (empty? changed-ns)
      (binding [env/*compiler* (:compiler-env build-config)]
        ;; TODO I want to change this signature to server build changed-ns
        (fig/notify-cljs-ns-changes
         build-config
         changed-ns)))))

(defn warning-message-handler [callback]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (callback (cljs.analyzer/message env s))))))

(defmulti report-exception (fn [exception cause] (:type cause)))

(defmethod report-exception :reader-exception [e {:keys [file line column]}]
  (println (format "ERROR: %s on file %s, line %d, column %d"
                   (some-> e (.getCause) (.getMessage))
                   file line column)))

(defmethod report-exception :default [e _]
  #_(clj-stacktrace.repl/pst+ e)
  (stack/print-stack-trace e 30))

(let [foreground-red "\u001b[31m"
      reset-color "\u001b[0m"]
  (defn handle-exceptions [figwheel-server {:keys [build-options exception id] :as build}]
    (println
     (str foreground-red "Compiling \"" (:output-to build-options) "\" failed."))
    (print reset-color)
    (let [cause (ex-data (.getCause exception))]
      (report-exception exception cause)
      (flush)
      (fig/notify-compile-error figwheel-server build {:exception exception :cause cause}))))

;; ware in all figwheel notifications
(defn build-hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              (conj cljs.analyzer/*cljs-warning-handlers*
                    (warning-message-handler
                     #(fig/notify-compile-warning figwheel-server build-config %)))]
      (try
        (binding [env/*compiler* (:compiler-env build-config)]
          (build-fn build-state)
          (notify-change-helper build-state changed-files))
        (catch Throwable e
          (handle-exceptions figwheel-server (assoc build-config :exception e)))))))


