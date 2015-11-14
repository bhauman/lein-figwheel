(ns user
  (:require
   [figwheel-sidecar.config :as config]   
   [figwheel-sidecar.system :as fs]
   [figwheel-sidecar.repl-api :as f]
   [com.stuartsierra.component :as component]
   [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(def sys f/*repl-api-system*)

(defn start []
  (f/start-figwheel!))

(defn stop []
  (f/stop-figwheel!))

(defn reset []
  (stop)
  (refresh :after 'user/start))

