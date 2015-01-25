(ns figwheel-sidecar.repl
  (:require
   [clojure.pprint :as p]
   [cljs.repl]
   [cljs.env :as env]
   [clojure.core.async :refer [chan <!! <! put! timeout close! go go-loop]]
   [figwheel-sidecar.core :as fig]))

(defn eval-js [{:keys [browser-callbacks] :as figwheel-server} js]
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
    (<!! out)))

;; limit how long we wait?
;; this is really rough we can wait for a the connection atom to
;; change for the positive
(defn wait-for-connection [{:keys [connection-count]}]
  (when (< @connection-count 1)
    (loop []
      (when (< @connection-count 1)
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
  ([figwheel-server {:keys [id] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})))
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

