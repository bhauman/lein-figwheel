(ns figwheel-sidecar.build-middleware.notifications
  (:require
   [figwheel-sidecar.components.figwheel-server :as server]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.cljs-utils.exception-parsing :as cljs-ex]
   [figwheel.core :as figcore]
   [cljs.env :as env]
   [cljs.util :refer [debug-prn]]
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
   [cljs.build.api :as build-api]
   [cljs.compiler]
   [cljs.repl]
   [clojure.stacktrace :as stack]
   [clj-stacktrace.core :refer [parse-exception]]
   [clj-stacktrace.repl :refer [pst-on]]
   [clojure.java.io :as io]
   [clojure.set]
   [clojure.string :as string]
   [clojure.walk]
   [clojure.data.json :as json]
   ;; dev
   #_[clojure.pprint :refer [pprint]]
   ))

(defn send-changed-namespaces
  "Formats and sends a files-changed message to the file-change-atom.
   Also reports this event to the console."
  [{:keys [build-id] :as figwheel-server} ns-syms]
  (when (not-empty ns-syms)
    (server/send-message-with-callback
     figwheel-server
     build-id
     {:msg-name :repl-eval
      :code (figcore/reload-namespace-code! ns-syms)}
     (fn [x] (clojure.pprint/pprint x)))
    (doseq [n ns-syms]
      (println "notifying browser that namespace changed: " (name n)))))

#_(defn file-changed?
  "Standard checksum to see if a file actually changed."
  [{:keys [file-md5-atom]} filepath]
  (when-let [file (io/file filepath)]
    (when (.exists file)
      (let [contents (slurp file)]
        (when (.contains contents "addDependency")
          (let [check-sum (.hashCode contents)
                changed? (not= (get @file-md5-atom filepath)
                               check-sum)]
            (swap! file-md5-atom assoc filepath check-sum)
            changed?))))))

#_(defn dependency-files [{:keys [output-to output-dir]}]
   [output-to (str output-dir "/goog/deps.js") (str output-dir "/cljs_deps.js")])

#_(defn get-dependency-files
  "Handling dependency files is different they don't have namespaces and their mtimes
   change on every compile even though their content doesn't. So we only want to include them
   when they change. This returns map representations that are ready to be sent to the client."
  [st]
  (keep
   #(when (file-changed? st %)
      { :dependency-file true
        :type :dependency-update
        :file (utils/remove-root-path %)
        :eval-body (slurp %)})
   (dependency-files st)))

#_(defn make-sendable-file
  "Formats a namespace into a map that is ready to be sent to the client."
  [st nm]
  (let [n (-> nm name utils/underscore)] ;; TODO I don't think this is needed
    { :file (str (build-api/target-file-for-cljs-ns nm (:output-dir st)))
      :namespace (cljs.compiler/munge n)
      :type :namespace}))

;; this is temporary until we fix up this api

(defn merge-build-into-server-state [figwheel-server {:keys [id build-options]}]
  (merge figwheel-server
         (if id {:build-id id} {})
         (select-keys build-options [:output-dir :output-to :recompile-dependents])))

(defn notify-cljs-ns-changes* [state ns-syms]
  (->> ns-syms

       #_(expand-to-dependents (:sources @env/*compiler*))
       #_(map (partial make-sendable-file state))
       #_(concat (get-dependency-files state))
       (send-changed-namespaces state)))

(defn notify-cljs-ns-changes [state build-config ns-syms]
  (notify-cljs-ns-changes*
   (merge-build-into-server-state state build-config)
   ns-syms))

(defn compile-error-occured [figwheel-server exception]
  (server/send-message figwheel-server
                       (:build-id figwheel-server)
                       {:msg-name :compile-failed
                        :exception-data (cljs-ex/parse-exception exception)})
  (cljs-ex/print-exception exception)
  (flush))

(defn notify-compile-error [server-state build-config {:keys [exception]}]
  (compile-error-occured
   (merge-build-into-server-state server-state build-config)
   exception))

(defn compile-warning-occured [figwheel-server msg]
  (server/send-message figwheel-server
                       (:build-id figwheel-server)
                       { :msg-name :compile-warning
                         :message msg }))

(defn notify-compile-warning [st build-config warning-msg]
  (compile-warning-occured (merge-build-into-server-state st build-config)
                           warning-msg))



;; change notifications

;; explore if we can simply forward javascript file names here and skip the
;; additional ns stuff for them

(defn notify-change-helper [{:keys [figwheel-server build-config additional-changed-ns]} files]
  (let [changed-ns (set (concat
                         (figcore/namespaces-for-paths files @(:compiler-env build-config))
                         (map symbol additional-changed-ns)))]
    (when-not (empty? changed-ns)
      (binding [env/*compiler* (:compiler-env build-config)]
        (notify-cljs-ns-changes
         figwheel-server
         build-config
         changed-ns)))))

(defn warning-message-handler [callback]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (let [warning-data {:line   (:line env)
                            :column (:column env)
                            :ns     (-> env :ns :name)
                            :file (if (= (-> env :ns :name) 'cljs.core)
                                    "cljs/core.cljs"
                                    ana/*cljs-file*)
                            :message s
                            :extra   extra}
              parsed-warning (cljs-ex/parse-warning warning-data)]
          (debug-prn (cljs-ex/format-warning warning-data))
          (callback parsed-warning))))))

(defn handle-exceptions [figwheel-server {:keys [build-options exception id] :as build}]
  (notify-compile-error figwheel-server build {:exception exception}))

(defn print-hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              [(#'warning-message-handler identity)]]
      (try
        (build-fn build-state)
        (catch Throwable e
          (cljs-ex/print-exception e)
          (flush))))))

;; ware in all figwheel notifications
(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              [(#'warning-message-handler
                #(notify-compile-warning figwheel-server build-config %))]]
      (try
        (binding [env/*compiler* (:compiler-env build-config)]
          (build-fn build-state)
          (notify-change-helper build-state changed-files))
        (catch Throwable e
          (handle-exceptions figwheel-server (assoc build-config :exception e)))))))
