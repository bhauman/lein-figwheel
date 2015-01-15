(ns figwheel-sidecar.repl
  (:require
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
(defn wait-for-connection [{:keys [connection-count]}]
  (when (< @connection-count 1)
    (<!! (go-loop []
           (when (< @connection-count 1)
             (timeout 500)
             (recur))))))

(defrecord FigwheelEnv [figwheel-server]
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (wait-for-connection figwheel-server))
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

(defn repl [build figwheel-server]
  (cljs.repl/repl* (repl-env figwheel-server build)
                   (assoc (or (:compiler build) (:build-options build))
                          :warn-on-undeclared true)))

