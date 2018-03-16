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

(defn repl-env-evaluate-callback [callback]
  (reify
    cljs.repl/IJavaScriptEnv
    (-setup [this opts])
    (-evaluate [_ _ _ js] (callback js))
    (-load [this ns url])
    (-tear-down [_] true)))

(defn send-changed-namespaces
  "Formats and sends a files-changed message to the file-change-atom.
   Also reports this event to the console."
  [{:keys [build-id] :as figwheel-server} ns-syms]
  (when (not-empty ns-syms)
    (binding [cljs.repl/*repl-env*
              (repl-env-evaluate-callback
               #(server/send-message-with-callback
                 figwheel-server
                 build-id
                 {:msg-name :repl-eval
                  :code %}
                 identity))]
      (figcore/reload-namespaces ns-syms))
    (doseq [n ns-syms]
      (println "notifying browser that namespace changed: " (name n)))))

(defn merge-build-into-server-state [figwheel-server {:keys [id build-options]}]
  (merge figwheel-server
         (if id {:build-id id} {})
         (select-keys build-options [:output-dir :output-to :recompile-dependents])))

(defn notify-cljs-ns-changes [state build-config ns-syms]
  (send-changed-namespaces
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
  (binding [env/*compiler* (:compiler-env build-config)]
    (let [changed-ns (figcore/paths->namespaces-to-reload files)]
      (when-not (empty? changed-ns)
        (notify-cljs-ns-changes
         figwheel-server
         build-config
         (if (get-in build-config [:build-options :reload-dependents] true)
           (figcore/expand-to-dependents changed-ns)
           changed-ns))))))

(defn warning-message-handler [{:keys [build-config figwheel-server]} callback]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (binding [cljs.repl/*repl-env*
                (repl-env-evaluate-callback
                 #(server/send-message-with-callback
                   figwheel-server
                   (:id build-config)
                   {:msg-name :repl-eval
                    :code %}
                   identity))
                cljs.env/*compiler* (:compiler-env build-config)]
        (figcore/handle-warnings [{:warning-type warning-type
                                   :env env
                                   :extra extra
                                   ;; capture path
                                   :path ana/*cljs-file*}]))
      (debug-prn (cljs-ex/format-warning (cljs-ex/extract-warning-data warning-type env extra)))
      (.flush *err*))))

(defn handle-exceptions [figwheel-server {:keys [build-options exception id compiler-env] :as build}]
  (when exception
    (binding [cljs.repl/*repl-env*
              (repl-env-evaluate-callback
               #(server/send-message-with-callback
                 figwheel-server
                 id
                 {:msg-name :repl-eval
                  :code %}
                 identity))
              cljs.env/*compiler* compiler-env]
      (figcore/handle-exception (Throwable->map exception)))
    (cljs-ex/print-exception exception))

  #_(notify-compile-error figwheel-server build {:exception exception}))

(defn print-hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              [(#'warning-message-handler build-state identity)]]
      (try
        (build-fn build-state)
        (catch Throwable e
          (cljs-ex/print-exception e)
          (flush))))))

;; ware in all figwheel notifications
(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              [(#'warning-message-handler build-state
                #(notify-compile-warning figwheel-server build-config %))]]
      (try
        (binding [env/*compiler* (:compiler-env build-config)]
          (build-fn build-state)
          (notify-change-helper build-state changed-files))
        (catch Throwable e
          (handle-exceptions figwheel-server (assoc build-config :exception e)))))))
