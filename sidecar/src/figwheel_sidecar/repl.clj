(ns figwheel-sidecar.repl
  (:require
   [cljs.repl]
   [cljs.util :refer [debug-prn]]
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

(defn load-javascript [figwheel-server ns url]
  (fig/send-message! figwheel-server :repl-load-js {:ns ns :url url}))

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
  (-load [this ns url]
    (wait-for-connection figwheel-server)
    (load-javascript figwheel-server ns url))
  (-tear-down [_] true))

(defn repl-env
  ([figwheel-server build]
   (assoc (FigwheelEnv. figwheel-server)
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defn repl [build figwheel-server]
  (cljs.repl/repl* (repl-env figwheel-server build)
                   (assoc (or (:compiler build) (:build-options build))
                          :warn-on-undeclared true)))
