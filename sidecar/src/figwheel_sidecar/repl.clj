(ns figwheel-sidecar.repl
  (:require
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [cljs.repl]
   [cljs.stacktrace]
   [cljs.util :refer [debug-prn]]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as string]
   [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-eval]

   [figwheel-sidecar.cljs-utils.exception-parsing :as cljs-ex]
   [figwheel-sidecar.components.figwheel-server :as server]
   [figwheel-sidecar.utils :refer [require?]]
   [figwheel-sidecar.config :as config]
   [strictly-specking-standalone.ansi-util :refer [with-color-when color]]   )
  (:import [clojure.lang IExceptionInfo]))

(defn eval-js [{:keys [browser-callbacks repl-eval-timeout] :as figwheel-server} js]
  (let [out (chan)
        repl-timeout (or repl-eval-timeout 8000)
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
    (let [[v ch] (alts!! [out (timeout repl-timeout)])]
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

(defn add-repl-print-callback! [{:keys [browser-callbacks repl-print-chan]}]
  ;; relying on the fact that we are running one repl at a time, not so good
  ;; we could create a better id here, we can add the build id at least
  (swap! browser-callbacks assoc "figwheel-repl-print"
         (fn [print-message] (put! repl-print-chan print-message))))

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

(defn clean-stacktrace [stack-trace]
  (map #(update-in % [:file]
                   (fn [x]
                     (when (string? x)
                       (first (string/split x #"\?")))))
       stack-trace))

(defrecord FigwheelEnv [figwheel-server repl-opts]
  cljs.repl/IReplEnvOptions
  (-repl-options [this]
    repl-opts)
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    ;; we need to print in the same thread as
    ;; the that the repl process was created in
    ;; thank goodness for the go loop!!
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (go-loop []
      (when-let [{:keys [stream args]}
                 (<! (:repl-print-chan figwheel-server))]
        (with-bindings @(::repl-writers figwheel-server)
          (if (= stream :err)
            (binding [*out* *err*]
              (apply println args)
              (flush))
            (do
              (apply println args)
              (flush))))
        (recur)))
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server js))
      ;; this is not used for figwheel
  (-load [this ns url]
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server (slurp url)))
  (-tear-down [_]
    (close! (:repl-print-chan figwheel-server))
    true)
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
            (filter valid-stack-line?
                    (cljs.repl/mapped-stacktrace (clean-stacktrace stacktrace)
                                                 build-options))]
      (println "  " (str function " (" (str (or url file)) ":" line ":" column ")")))
    (flush)))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build} repl-opts]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               {::repl-writers (atom {:out *out*
                                                      :err *err*})}
                               (select-keys build-options [:output-dir :output-to]))
                        repl-opts)
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server build]
   (repl-env figwheel-server build nil))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server nil)))

;; add some repl functions for reloading local clj code

(defmulti start-cljs-repl (fn [protocol figwheel-env]
                            protocol))

(defmethod start-cljs-repl :nrepl
  [_ figwheel-env]
  (try
    (cond
      (and (require? 'figwheel.tools.nrepl)
           (when-let [present-var (resolve 'figwheel.tools.nrepl/*cljs-evaluator*)]
             (thread-bound? present-var)))
      (let [cljs-repl (resolve 'figwheel.tools.nrepl/cljs-repl)
            opts' (:repl-opts figwheel-env)]
        (apply cljs-repl figwheel-env (apply concat opts')))
      (and (require? 'cemerick.piggieback)
           (when-let [present-var (resolve 'cemerick.piggieback/*cljs-repl-env*)]
             (thread-bound? present-var)))
      (let [cljs-repl (resolve 'cemerick.piggieback/cljs-repl)
            opts' (:repl-opts figwheel-env)]
        (apply cljs-repl figwheel-env (apply concat opts')))
      :else (throw (ex-info "Unable to load a ClojureScript nREPL middleware library" {})))
    (catch Exception e
      (println "!!!" (.getMessage e))
      (let [message "Failed to launch Figwheel CLJS REPL: nREPL connection found but unable to load piggieback.
This is commonly caused by
 A) not providing piggieback as a dependency and/or
 B) not adding piggieback middleware into your nrepl middleware chain.

example profile.clj code:
-----
:profiles {:dev {:dependencies [[com.cemerick/piggieback <current-version>]
                                [org.clojure/tools.nrepl  <current-version>]]
                 :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
-----
Please see the documentation for piggieback here https://github.com/cemerick/piggieback#installation

Note: Cider will inject this config into your project.clj.
This can cause confusion when your are not using Cider."]
        (throw (Exception. message))))))

(defmethod start-cljs-repl :default
  [_ figwheel-env]
  (cljs.repl/repl* figwheel-env (:repl-opts figwheel-env)))

(defn in-nrepl-env? []
  (thread-bound? #'nrepl-eval/*msg*))

(defn catch-exception
  ([e repl-env opts form env]
   (if (and (instance? IExceptionInfo e)
            (#{:js-eval-error :js-eval-exception} (:type (ex-data e))))
     (cljs.repl/repl-caught e repl-env opts)
     ;; color is going to have to be configurable
     (with-color-when (-> repl-env :figwheel-server :ansi-color-output)
       (cljs-ex/print-exception e (cond-> {:environment :repl
                                           :current-ns ana/*cljs-ns*}
                                    form (assoc :source-form form)
                                    env  (assoc :compile-env env))))))
  ([e repl-env opts]
   (catch-exception e repl-env opts nil nil)))

(defn warning-handler [repl-env form opts]
  (fn [warning-type env extra]
    (when-let [warning-data (cljs-ex/extract-warning-data warning-type env extra)]
      (debug-prn (with-color-when (-> repl-env :figwheel-server :ansi-color-output)
                   (cljs-ex/format-warning (assoc warning-data
                                                  :source-form form
                                                  :current-ns ana/*cljs-ns*
                                                  :environment :repl)))))))

(defn- wrap-fn [form]
  (cond
    (and (seq? form)
         (#{'ns 'require 'require-macros
            'use 'use-macros 'import 'refer-clojure} (first form)))
    identity

    ('#{*1 *2 *3 *e} form)
    (fn [x] `((if (and
                   (cljs.core/exists? js/figwheel)
                   (cljs.core/exists? js/figwheel.client.repl_result_pr_str))
                js/figwheel.client.repl_result_pr_str
                cljs.core.pr-str)
              ~x))
    :else
    (fn [x]
      `(try
         ((if (and
               (cljs.core/exists? js/figwheel)
               (cljs.core/exists? js/figwheel.client.repl_result_pr_str))
            js/figwheel.client.repl_result_pr_str
            cljs.core.pr-str)
          (let [ret# ~x]
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 ret#)
            ret#))
         (catch :default e#
           (set! *e e#)
           (throw e#))))))

(defn catch-warnings-and-exceptions-eval-cljs
  ([repl-env env form]
   (catch-warnings-and-exceptions-eval-cljs
    repl-env env form cljs.repl/*repl-opts*))
  ([repl-env env form opts]
   (try
     (binding [cljs.analyzer/*cljs-warning-handlers*
               [(warning-handler repl-env form opts)]]
       (#'cljs.repl/eval-cljs repl-env env form opts))
     (catch Throwable e
       (catch-exception e repl-env opts form env)
       ;; when we are in an nREPL environment lets re-throw with a friendlier
       ;; message maybe
       #_(when (in-nrepl-env?)
           (throw (ex-info "Hey" {})))))))

(defn connection-count [figwheel-server build-id]
  (get (server/connection-data figwheel-server) build-id))

(defn prompt-fn [figwheel-server build-id]
  (if (in-nrepl-env?)
    #(when-let [c (connection-count figwheel-server build-id)]
       (when (> c 1)
         (with-color-when (-> figwheel-server :ansi-color-output)
           (println
            (color
             (str "v------- " build-id "!{:conn " c "} -------")
             :magenta)))))
    #(print
      (str
       (when build-id (str build-id ":"))
       ana/*cljs-ns*
       (when-let [c (connection-count figwheel-server build-id)]
         (when (< 1 c) (str "!{:conn " c "}")))
       "=> "))))

(defn cljs-repl-env
  ([build figwheel-server]
   (cljs-repl-env build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true
                            :wrap  wrap-fn
                            :prompt (prompt-fn figwheel-server (:id build))
                            :eval #'catch-warnings-and-exceptions-eval-cljs)
                     opts)
         figwheel-server (assoc figwheel-server
                                :repl-print-chan (chan))
         figwheel-repl-env (repl-env figwheel-server build)
         repl-opts (merge
                     {:compiler-env (:compiler-env build)
                      :special-fns cljs.repl/default-special-fns
                      :output-dir "out"}
                     opts)]
     (assoc figwheel-repl-env
            ;; these are merged to opts by cljs.repl/repl*
            :repl-opts repl-opts))))

(defn repl
  [& args]
  (start-cljs-repl
    (if (in-nrepl-env?)
      :nrepl
      :default)
    (apply cljs-repl-env args)))

;; deprecated
(defn get-project-cljs-builds []
  (-> (config/fetch-config) :data :all-builds))
