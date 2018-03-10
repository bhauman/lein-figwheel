(ns figwheel-sidecar.build-middleware.clj-reloading
  (:require
   [cljs.env :as env]
   [figwheel.core :as figcore]
   [figwheel-sidecar.build-middleware.notifications :as notify]
   [figwheel-sidecar.components.figwheel-server :as server]
   [figwheel-sidecar.utils :as utils]))

(defn default-config [{:keys [reload-clj-files] :as figwheel-server}]
  (cond
    (false? reload-clj-files) false
    (or (true? reload-clj-files)
          (not (map? reload-clj-files)))
    {:cljc true :clj true}
    :else reload-clj-files))

(defn suffix-conditional [config]
  #(reduce-kv
    (fn [accum k v]
      (or accum
          (and v
               (.endsWith % (str "." (name k))))))
    false
    config))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (let [reload-config (default-config figwheel-server)]
      (if-let [changed-clj-files (and
                                  reload-config
                                  (not-empty
                                   (filter (suffix-conditional reload-config)
                                           changed-files)))]
        (try
          (let [additional-changed-ns'
                (binding [env/*compiler* (:compiler-env build-config)]
                  (figcore/reload-clj-files changed-files))]
            (build-fn
             (update
              build-state
              :changed-files
              #(distinct
                (concat (remove (fn [x] (.endsWith x ".clj")) %)
                        changed-clj-files))))
            (let [first-file (first changed-clj-files)]
              ;; only notify if there are no other successful
              ;; notifications going to the client
              (when (and (= 1 (count changed-clj-files))
                         (.endsWith first-file ".clj"))
                (server/send-message figwheel-server
                                     (:id build-config)
                                     { :msg-name :files-changed
                                      :files [{:file first-file :type :file}]}))))

          (catch Throwable e
            (notify/handle-exceptions figwheel-server (assoc build-config :exception e))))
        (build-fn
         (update build-state :changed-files (fn [files] (remove #(.endsWith % ".clj") files))))))))
