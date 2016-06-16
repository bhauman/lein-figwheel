(ns user
  (:require
   [figwheel-sidecar.repl-api :as f]
   [figwheel-sidecar.config   :as fc]
   [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(defn start []
  (f/start-figwheel!))

(defn cljs-repl []
  (f/cljs-repl))

(defn stop []
  (f/stop-figwheel!))

(defn reset []
  (println "Removing system")
  (f/remove-system)
  (refresh :after 'user/start))

