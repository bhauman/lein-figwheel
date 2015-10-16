(ns figwheel-sidecar.components.figwheel-server
  (:require
   [figwheel-sidecar.core :as fig]
   [com.stuartsierra.component :as component]))

(defrecord FigwheelServer []
  component/Lifecycle
  (start [this]
    (if-not (:http-server this)
      (do
        (map->FigwheelServer (fig/start-server this)))
      this))
  (stop [this]
    (when (:http-server this)
      (println "Figwheel: Stopping Server")
      (fig/stop-server this))
    (dissoc this :http-server)))

(defn figwheel-server [figwheel-options]
  (map->FigwheelServer figwheel-options))
