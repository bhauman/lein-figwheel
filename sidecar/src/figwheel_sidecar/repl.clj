(ns figwheel-sidecar.repl
  (:require
   [figwheel-sidecar.cljs-utils.exception-parsing :as cljs-ex]
   [figwheel-sidecar.config-check.ansi :refer [with-color]]
   [cljs.repl]
   [cljs.stacktrace]
   [cljs.analyzer :as ana]   
   [cljs.env :as env]
   [cljs.util :refer [debug-prn]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]

   [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-eval]
   [figwheel-sidecar.components.figwheel-server :as server]
   
   [figwheel-sidecar.config :as config])
  (:import [clojure.lang IExceptionInfo]))

;; slow but works
;; TODO simplify in the future
(defn resolve-repl-println []
  (let [opts (resolve 'cljs.repl/*repl-opts*)]
    (or (and opts (:print @opts))
        println)))

(defn repl-println [& args]
  (apply (resolve-repl-println) args))

(defn eval-js [{:keys [browser-callbacks] :as figwheel-server} js]
  (let [out (chan)
        callback (fn [result]
                   (put! out result)
                   (go
                     (<! (timeout 2000))
                     (close! out)))]
    (server/send-message-with-callback figwheel-server
                                       (:build-id figwheel-server)
                                       {:msg-name :repl-eval
                                        :code js}
                                       callback)
    (let [[v ch] (alts!! [out (timeout 8000)])]
      (if (= ch out)
        v
        {:status :exception
         :value "Eval timed out!"
         :stacktrace "No stacktrace available."}))))

(defn connection-available?
  [figwheel-server build-id]
  (let [connection-count (server/connection-data figwheel-server)]
    (not
     (zero?
      (+ (or (get connection-count build-id) 0)
         (or (get connection-count nil) 0))))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [build-id] :as figwheel-server}]
  (when-not (connection-available? figwheel-server build-id)
    (loop []
      (when-not (connection-available? figwheel-server build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks]}]
  (swap! browser-callbacks assoc "figwheel-repl-print"
         (fn [args] (apply repl-println args))))

(defn valid-stack-line? [{:keys [function file url line column]}]
  (and (not (nil? function))
       (not= "NO_SOURCE_FILE" file)))

(defn extract-host-and-port [base-path]
  (let [[host port] (-> base-path
                      string/trim
                      (string/replace-first #".*:\/\/" "")
                      (string/split #"\/")
                      first
                      (string/split #":"))]
    (if host
      (if-not port
        {:host host}
        {:host host :port (Integer/parseInt port)})
      {})))

(defrecord FigwheelEnv [figwheel-server]
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server js))
      ;; this is not used for figwheel
  (-load [this ns url]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server (slurp url)))
  (-tear-down [_] true)
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [repl-env stacktrace error build-options]
    (cljs.stacktrace/parse-stacktrace (merge repl-env
                                             (extract-host-and-port (:base-path error)))
                                      (:stacktrace error)
                                      {:ua-product (:ua-product error)}
                                      build-options))
  cljs.repl/IPrintStacktrace
  (-print-stacktrace [repl-env stacktrace error build-options]
    (doseq [{:keys [function file url line column] :as line-tr}
            (filter valid-stack-line? (cljs.repl/mapped-stacktrace stacktrace build-options))]
      (repl-println "\t" (str function " (" (str (or url file)) ":" line ":" column ")")))))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               (select-keys build-options [:output-dir :output-to])))
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defmulti start-cljs-repl (fn [protocol figwheel-env opts]
                            protocol))

(defmethod start-cljs-repl :nrepl
  [_ figwheel-env opts]
  (try
    (require 'cemerick.piggieback)
    (let [cljs-repl (resolve 'cemerick.piggieback/cljs-repl)
          special-fns (or (:special-fns opts) cljs.repl/default-special-fns)
          output-dir (or (:output-dir opts) "out")
          opts' (assoc opts
                       :special-fns special-fns
                       :output-dir output-dir)]
      (try
        ;; Piggieback version 0.2+
        (apply cljs-repl figwheel-env (apply concat opts'))
        (catch Exception e
          ;; Piggieback version 0.1.5
          (apply cljs-repl
                 (apply concat
                        (assoc opts'
                               :repl-env figwheel-env))))))
    (catch Exception e
      (let [message "Failed to launch Figwheel CLJS REPL: nREPL connection found but unable to load piggieback.\nPlease install https://github.com/cemerick/piggieback"]
        (println message)
        (throw (Exception. message))))))

(defmethod start-cljs-repl :default
  [_ figwheel-env opts]
  (cljs.repl/repl* figwheel-env opts))

(defn in-nrepl-env? []
  (thread-bound? #'nrepl-eval/*msg*))

(defn catch-exception
  ([e repl-env opts form env]
   (if (and (instance? IExceptionInfo e)
            (#{:js-eval-error :js-eval-exception} (:type (ex-data e))))
     (cljs.repl/repl-caught e repl-env opts)
     ;; color is going to have to be configurable
     (with-color
       (cljs-ex/print-exception e (cond-> {:environment :repl
                                           :current-ns ana/*cljs-ns*}
                                    form (assoc :source-form form)
                                    env  (assoc :compile-env env))))))
  ([e repl-env opts]
   (catch-exception e repl-env opts nil nil)))

;; this is copied because its private
(defn wrap-fn [form]
  (cond
    (and (seq? form) (= 'ns (first form))) identity
    ('#{*1 *2 *3 *e} form) (fn [x] `(cljs.core.pr-str ~x))
    :else
    (fn [x]
      `(try
         (cljs.core.pr-str
           (let [ret# ~x]
             (set! *3 *2)
             (set! *2 *1)
             (set! *1 ret#)
             ret#))
         (catch :default e#
           (set! *e e#)
           (throw e#))))))

;; this is copied because it's private
(defn eval-cljs
  ([repl-env env form]
    (eval-cljs repl-env env form cljs.repl/*repl-opts*))
  ([repl-env env form opts]
   (cljs.repl/evaluate-form repl-env
     (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
     "<cljs repl>"
     form
     ;; the pluggability of :wrap is needed for older JS runtimes like Rhino
     ;; where catching the error will swallow the original trace
     ((or (:wrap opts) wrap-fn) form)
     opts)))

(defn warning-handler [form opts]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (let [warning-data {:line   (:line env)
                            :column (:column env)
                            :ns     (-> env :ns :name)
                            :file (if (= (-> env :ns :name) 'cljs.core)
                                    "cljs/core.cljs"
                                    ana/*cljs-file*)
                            :source-form   form
                            :message s
                            :extra   extra}
              parsed-warning (cljs-ex/parse-warning warning-data)]
          (debug-prn (with-color
                       (cljs-ex/format-warning warning-data))))))))

(defn catch-warnings-and-exceptions-eval-cljs
  ([repl-env env form]
   (catch-warnings-and-exceptions-eval-cljs
    repl-env env form cljs.repl/*repl-opts*))
  ([repl-env env form opts]
   (try
     (binding [cljs.analyzer/*cljs-warning-handlers*
               [(warning-handler form opts)]]
       (eval-cljs repl-env env form opts))
     (catch Throwable e
       (catch-exception e repl-env opts form env)
       ;; when we are in an nREPL environment lets re-throw with a friendlier
       ;; message maybe
       #_(when (in-nrepl-env?)
           (throw (ex-info "Hey" {})))))))

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true
                            :eval #'catch-warnings-and-exceptions-eval-cljs
                            )
                     opts)
         figwheel-repl-env (repl-env figwheel-server build)
         repl-opts (assoc opts :compiler-env (:compiler-env build))
         protocol (if (in-nrepl-env?)
                    :nrepl
                    :default)]
     (start-cljs-repl protocol figwheel-repl-env repl-opts))))

;; deprecated 
(defn get-project-cljs-builds []
  (:all-builds (config/fetch-config)))
