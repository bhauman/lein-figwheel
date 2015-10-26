(ns figwheel-sidecar.build-hooks.notifications
  (:require
   [figwheel-sidecar.channel-server :as server]
   [figwheel-sidecar.utils :as utils]   

   [cljs.env :as env]
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
   [cljs.build.api :as build-api]
   [cljs.compiler]

   [clojure.stacktrace :as stack]
   [clj-stacktrace.core :refer [parse-exception]]
   [clj-stacktrace.repl :refer [pst-on]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [digest]
   ))

(defn find-figwheel-meta []
  (into {}
        (map
         (fn [n]
           [(cljs.compiler/munge (name n))
            (select-keys (meta n) [:figwheel-always :figwheel-load :figwheel-no-load])])
         (filter (fn [n] (let [m (meta n)]
                          (or
                           (get m :figwheel-always)
                           (get m :figwheel-load)
                           (get m :figwheel-no-load))))
                 (ana-api/all-ns)))))

(defn send-changed-files
  "Formats and sends a files-changed message to the file-change-atom.
   Also reports this event to the console."
  [{:keys [build-id] :as figwheel-server} files]
  (when (not-empty files)
    (server/send-message figwheel-server
                         build-id
                         { :msg-name :files-changed
                           :files files
                           :recompile-dependents (:recompile-dependents figwheel-server)
                           :figwheel-meta (find-figwheel-meta)})
    (doseq [f files]
      (println "notifying browser that file changed: " (:file f)))))

(defn file-changed?
  "Standard md5 check to see if a file actually changed."
  [{:keys [file-md5-atom]} filepath]  
  (when-let [file (io/file filepath)]
    (when (.exists file)
      (let [contents (slurp file)]
        (when (.contains contents "addDependency")
          (let [check-sum (digest/md5 contents)
                changed? (not= (get @file-md5-atom filepath)
                               check-sum)]
            (swap! file-md5-atom assoc filepath check-sum)
            changed?))))))

(defn dependency-files [{:keys [output-to output-dir]}]
   [output-to (str output-dir "/goog/deps.js") (str output-dir "/cljs_deps.js")])

(defrecord DependencyFile [dependency-file type file eval-body])
(defmethod print-method DependencyFile [o ^java.io.Writer w]
  (let [inspectable (dissoc o :eval-body)]
    (print-method inspectable w)))

(defn get-dependency-files
  "Handling dependency files is different they don't have namespaces and their mtimes
   change on every compile even though their content doesn't. So we only want to include them
   when they change. This returns map representations that are ready to be sent to the client."
  [st]
  (keep
   #(when (file-changed? st %)
      (map->DependencyFile
       {:dependency-file true
        :type :dependency-update
        :file (utils/remove-root-path %)
        :eval-body (slurp %)}))
   (dependency-files st)))

(defn make-sendable-file
  "Formats a namespace into a map that is ready to be sent to the client."
  [st nm]
  (let [n (-> nm name utils/underscore)] ;; TODO I don't think this is needed
    { :file (str (build-api/target-file-for-cljs-ns nm))
      :namespace (cljs.compiler/munge n)
      :type :namespace}))

;; this is temporary until we fix up this api

(defn merge-build-into-server-state [figwheel-server {:keys [id build-options]}]
  (merge figwheel-server
         (if id {:build-id id} {})
         (select-keys build-options [:output-dir :output-to :recompile-dependents])))

(defn notify-cljs-ns-changes* [state ns-syms]
  (->> ns-syms
    (map (partial make-sendable-file state))
    (concat (get-dependency-files state))
    (send-changed-files state)))

(defn notify-cljs-ns-changes [state build-config ns-syms]
  (notify-cljs-ns-changes*
   (merge-build-into-server-state state build-config)
   ns-syms))

(defn compile-error-occured [figwheel-server exception cause]
  (let [parsed-exception (parse-exception exception)
        formatted-exception (let [out (java.io.ByteArrayOutputStream.)]
                              (pst-on (io/writer out) false exception)
                              (.toString out))]
    (server/send-message figwheel-server
                          (:build-id figwheel-server)
                          { :msg-name :compile-failed
                            :exception-data parsed-exception
                            :formatted-exception formatted-exception
                            :cause cause })))

(defn notify-compile-error [server-state build-config {:keys [exception cause]}]
  (compile-error-occured
   (merge-build-into-server-state server-state build-config)
   exception
   cause))

(defn compile-warning-occured [figwheel-server msg]
  (server/send-message figwheel-server
                       (:build-id figwheel-server)
                       { :msg-name :compile-warning
                         :message msg }))

(defn notify-compile-warning [st build-config warning-msg]
  (compile-warning-occured (merge-build-into-server-state st build-config)
                           warning-msg))

;; change notifications

(defn notify-change-helper [{:keys [figwheel-server build-config additional-changed-ns]} files]
  (let [changed-ns (set (concat
                         (keep utils/get-ns-from-source-file-path
                              (filter #(or (.endsWith % ".cljs")
                                           (.endsWith % ".cljc")) files))
                         additional-changed-ns))]
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
      (notify-compile-error figwheel-server build {:exception exception :cause cause}))))

;; ware in all figwheel notifications
(defn build-hook [figwheel-server build-fn]
  (fn [{:keys [build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              (conj cljs.analyzer/*cljs-warning-handlers*
                    (warning-message-handler
                     #(notify-compile-warning figwheel-server build-config %)))]
      (try
        (binding [env/*compiler* (:compiler-env build-config)]
          (build-fn build-state)
          (notify-change-helper (assoc build-state :figwheel-server figwheel-server) changed-files))
        (catch Throwable e
          (handle-exceptions figwheel-server (assoc build-config :exception e)))))))


