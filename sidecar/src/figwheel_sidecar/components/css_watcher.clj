(ns figwheel-sidecar.components.css-watcher
  (:require
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.watching :refer [watcher]]
   [figwheel-sidecar.utils :as utils]
   [com.stuartsierra.component :as component]
   [clojure.java.io :as io]))

(defn make-css-file [state path]
  { :file (utils/remove-root-path path)
    :type :css } )

(defn send-css-files [st files]
  (fig/send-message! st :css-files-changed { :files files}))

(defn notify-css-file-changes [st files]
  (send-css-files st (map #(make-css-file st %) files)))

(defn handle-css-notification [figwheel-server files]
  (let [changed-css-files (filter #(.endsWith % ".css") files)]
    (when (not-empty changed-css-files)
      (notify-css-file-changes figwheel-server changed-css-files)
      (doseq [f files]
        (println "sending changed CSS file:" (:file f))))))

(defrecord CSSWatcher [css-dirs figwheel-server log-writer]
  component/Lifecycle
  (start [this]
         (if (not (:css-watcher-quit this))
           (do
             (if (not-empty (:css-dirs this))
               (let [log-writer (or log-writer (io/writer "figwheel_server.log" :append true))]
                 (println "Figwheel: Starting CSS watcher for dirs " (pr-str (:css-dirs this)))
                 (assoc this :css-watcher-quit
                        (watcher (:css-dirs this)
                                 (fn [files]
                                   (utils/sync-exec
                                    (fn []
                                      (binding [*out* log-writer]
                                        (#'handle-css-notification
                                         (:figwheel-server this) files))))))))
               (do
                 (println "Figwheel: No CSS directories configured")
                 this)))
           (do
             (println "Figwheel: Already watching CSS")
             this)))
  (stop [this]
        (when (:css-watcher-quit this)
          (println "Figwheel: Stopped watching CSS")
          (reset! (:css-watcher-quit this) true))
    (dissoc this :css-watcher-quit)))

(defn css-watcher [css-dirs]
  (map->CSSWatcher {:css-dirs css-dirs}))
