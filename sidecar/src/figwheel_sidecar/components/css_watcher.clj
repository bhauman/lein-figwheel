(ns figwheel-sidecar.components.css-watcher
  (:require
   [figwheel-sidecar.components.figwheel-server :as fig]
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]
   [com.stuartsierra.component :as component]
   [clojure.java.io :as io]))

(defn make-css-file [path]
  { :file (utils/remove-root-path path)
    :type :css } )

(defn send-css-files [figwheel-server files]
  (fig/send-message figwheel-server
                    ::fig/broadcast
                    { :msg-name :css-files-changed
                      :files files}))

(defn handle-css-notification [figwheel-server files]
  (when-let [changed-css-files (not-empty (filter #(.endsWith % ".css") (map str files)))]
    (let [sendable-files (map #(make-css-file %) changed-css-files)]
      (send-css-files figwheel-server sendable-files)
      (doseq [f sendable-files]
        (println "sending changed CSS file:" (:file f))))))

(defrecord CSSWatcher [css-dirs figwheel-server log-writer]
  component/Lifecycle
  (start [this]
         (if (not (:css-watcher-quit this))
           (do
             (if (not-empty (:css-dirs this))
               (let [log-writer (or log-writer
                                    (:log-writer figwheel-server)
                                    (io/writer "figwheel_server.log" :append true))]
                 (println "Figwheel: Starting CSS watcher for dirs " (pr-str (:css-dirs this)))
                 (assoc this :css-watcher-quit
                        (watching/watch! (:css-dirs this)
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
          (watching/stop! (:css-watcher-quit this)))
    (dissoc this :css-watcher-quit)))

(defn css-watcher [{:keys [css-dirs] :as options}]
  (map->CSSWatcher options))
