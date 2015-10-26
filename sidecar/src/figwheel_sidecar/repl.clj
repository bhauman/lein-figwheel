(ns figwheel-sidecar.repl
  (:require
   [cljs.repl]
   [cljs.stacktrace]
   [cljs.env :as env]

   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]

   [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-eval]
   [figwheel-sidecar.channel-server :as server]
   
   [figwheel-sidecar.config :as config]))

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
  (let [pr-fn (resolve-repl-println)]
    (swap! browser-callbacks assoc "figwheel-repl-print"
           (fn [args] (apply pr-fn args)))))

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
          special-fns (or (:special-fns opts) cljs.repl/default-special-fns)]
      (try
        ;; Piggieback version 0.2+
        (cljs-repl figwheel-env :special-fns special-fns)
        (catch Exception e
          ;; Piggieback version 0.1.5
          (cljs-repl :repl-env figwheel-env :special-fns special-fns))))
    (catch Exception e
      (println "INFO: nREPL connection found but unable to load piggieback. Starting default REPL")
      (start-cljs-repl :default figwheel-env opts))))

(defmethod start-cljs-repl :default
  [_ figwheel-env opts]
  (cljs.repl/repl* figwheel-env opts))

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true)
                     opts)
         figwheel-repl-env (repl-env figwheel-server build)
         repl-opts (assoc opts :compiler-env (:compiler-env build))
         protocol (if (thread-bound? #'nrepl-eval/*msg*)
                    :nrepl
                    :default)]
     (start-cljs-repl protocol figwheel-repl-env repl-opts))))

