(ns figwheel-sidecar.components.file-system-watcher
  (:require
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.components.figwheel-server :refer [config-options]]
   [com.stuartsierra.component :as component]
   [clojure.java.io :as io]))

(defrecord FileSystemWatcher [watcher-name watch-paths notification-handler figwheel-server log-writer]
  component/Lifecycle
  (start [this]
    (let [figwheel-server-options (config-options figwheel-server)]
      (if (not (:file-system-watcher-quit this))
        (do
          (if (not-empty watch-paths)
            (let [log-writer (or log-writer
                                 (:log-writer figwheel-server-options)
                                 (io/writer "figwheel_server.log" :append true))]
              (println "Figwheel: Starting" watcher-name "for paths " (pr-str watch-paths))
              (assoc this :file-system-watcher-quit
                     (watching/watch!
                      (:hawk-options figwheel-server-options)
                      watch-paths
                      (fn [files]
                        (utils/sync-exec
                         (fn []
                           (binding [*out* log-writer]
                             (notification-handler this files))))))))
            (do
              (println "Figwheel: No watch paths configured for" watcher-name)
              this)))
        (do
          (println "Figwheel: Already watching" watcher-name "paths " (pr-str watch-paths))
          this))))
  (stop [this]
    (when (:file-system-watcher-quit this)
      (println "Figwheel: Stopped watching" watcher-name "paths " (pr-str watch-paths))
      (watching/stop! (:file-system-watcher-quit this)))
    (dissoc this :file-system-watcher-quit)))

(defn file-system-watcher [{:keys [watcher-name watch-paths notification-handler] :as options}]
  (map->FileSystemWatcher options))
