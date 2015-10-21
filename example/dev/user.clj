(ns user
  (:require
   [figwheel-sidecar.config :as config]   
   [figwheel-sidecar.system :as fs]
   [figwheel-sidecar.repl-api :as f]
   [com.stuartsierra.component :as component]
   [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(def temp-config
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      ; :nrepl-port 7888
                      }
   :build-ids  ["example"]
   :all-builds (config/get-project-builds)})

(defn start []
  (f/start-figwheel! temp-config))

(defn stop []
  (f/stop-figwheel!))

(defn reset []
  (stop)
  (refresh :after 'user/start))



