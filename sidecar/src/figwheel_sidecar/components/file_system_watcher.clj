(ns figwheel-sidecar.components.file-system-watcher
  (:require
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.components.figwheel-server :refer [config-options]]
   [com.stuartsierra.component :as component]
   [clojure.java.io :as io]))

;; watch-paths are created if they don't exist
;; This could easily cause problems if people put file names in the
;; list of watch paths
;; But file names are not supposed to be in the watch paths
(defn ensure-watch-paths [watch-paths]
  (doseq [watch-path watch-paths]
    (when-not (.exists (io/file watch-path))
      (.mkdirs (io/file watch-path)))))

(defrecord FileSystemWatcher [watcher-name watch-paths notification-handler figwheel-server log-writer]
  component/Lifecycle
  (start [this]
    (let [figwheel-server-options (config-options figwheel-server)]
      (if (not (:file-system-watcher-quit this))
        (do
          (ensure-watch-paths watch-paths)
          (if (not-empty watch-paths)
            (let [log-writer (or log-writer
                                 (:log-writer figwheel-server-options)
                                 #_(io/writer "figwheel_server.log" :append true))]
              (println "Figwheel: Starting" watcher-name "for paths " (pr-str watch-paths))
              (assoc this :file-system-watcher-quit
                     (watching/watch!
                      (:hawk-options figwheel-server-options)
                      watch-paths
                      (fn [files]
                        (utils/sync-exec
                         (fn []
                           (utils/bind-logging
                            log-writer
                            (notification-handler this files)))))
                      (:wait-time-ms figwheel-server-options))))
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
