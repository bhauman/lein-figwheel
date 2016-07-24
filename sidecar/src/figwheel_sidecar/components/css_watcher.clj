(ns figwheel-sidecar.components.css-watcher
  (:require
   [figwheel-sidecar.components.file-system-watcher :as fsw]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.components.figwheel-server :as fig]
   [com.stuartsierra.component :as component]))

(defn make-css-file [path]
  { :file (utils/remove-root-path path)
    :type :css } )

(defn send-css-files [figwheel-server files]
  (fig/send-message figwheel-server
                    ::fig/broadcast
                    { :msg-name :css-files-changed
                      :files files}))

(defn handle-css-notification [watcher files]
  (when-let [changed-css-files (not-empty (filter #(.endsWith % ".css") (map str files)))]
    (let [figwheel-server (:figwheel-server watcher)
          sendable-files (map #(make-css-file %) changed-css-files)]
      (send-css-files figwheel-server sendable-files)
      (doseq [f sendable-files]
        (println "Notifyng browser that CSS file changed:" (:file f))))))

(defn css-watcher [{:keys [watch-paths] :as options}]
  (fsw/file-system-watcher
   (merge {:watcher-name "CSS Watcher"
           :notification-handler handle-css-notification} options)))
