(ns figwheel-sidecar.repl
  (:require
   [clojure.pprint :as p]
   [cljs.repl]
   [cljs.util]
   [cljs.env :as env]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [figwheel-sidecar.core :as fig]))

(defn fix-stacktrace [{:keys [status stacktrace value] :as eval-resp} output-dir]
  (if (and (= status :exception) (vector? stacktrace))
    (assoc eval-resp
           :stacktrace (if (>= (:qualifier cljs.util/*clojurescript-version*) 2814)
                         (mapv (fn [{:keys [file] :as x}]
                                 (assoc x :file (str output-dir "/" file)))
                               stacktrace)
                         (string/join "\n" (map (fn [{:keys [function file line column]}]
                                                  (str "\t" function " (" file ":" line ":" column ")"))
                                                stacktrace))))
    eval-resp))

(defn eval-js [{:keys [browser-callbacks output-dir] :as figwheel-server} js]
  (let [callback-name (str (gensym "repl_eval_"))
        out (chan)
        callback (fn [result]
                   (swap! browser-callbacks dissoc callback-name)
                   (put! out result)
                   (go
                     (<! (timeout 2000))
                     (close! out)))]
    (swap! browser-callbacks assoc callback-name callback)
    (fig/send-message! figwheel-server :repl-eval {:code js :callback-name callback-name})
    (let [[v ch] (alts!! [out (timeout 8000)])]
      (if (= ch out)
        (fix-stacktrace v output-dir)
        {:status :exception
         :value "Eval timed out!"
         :stacktrace "No stacktrace available."}))))

(defn connection-available?
  [connection-count build-id]
  (not
   (zero?
    (+ (or (get @connection-count build-id) 0)
       (or (get @connection-count nil) 0)))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [connection-count build-id]}]
  (when-not (connection-available? connection-count build-id)
    (loop []
      (when-not (connection-available? connection-count build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks]}]
  (swap! browser-callbacks assoc "figwheel-repl-print"
         (fn [args] (apply println args))))

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
  (-load [this ns url] true)
  (-tear-down [_] true))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               (select-keys build-options [:output-dir :output-to])))
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (cljs.repl/repl* (repl-env figwheel-server build)
                    (merge (assoc (or (:compiler build) (:build-options build))
                                  :warn-on-undeclared true)
                           opts))))

