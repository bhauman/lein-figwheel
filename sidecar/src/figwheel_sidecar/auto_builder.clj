(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.string :as string]
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.system :as system]))

;; THESE ARE ALL FOR LEGACY and are DEPRECATED

(defn autobuild* [{:keys [builds figwheel-server]}]
  (system/start-figwheel!
   {:all-builds builds
    :build-ids (mapv :id (config/narrow-builds* builds []))
    :figwheel-options figwheel-server}))

(defn autobuild-ids [{:keys [all-builds build-ids figwheel-server]}]
  (let [builds (config/narrow-builds* all-builds build-ids)
        errors (config/check-config figwheel-server builds :print-warning true)]
    (if (empty? errors)
      (do
        (autobuild* {:builds builds
                     :figwheel-server figwheel-server}))
      (do
        (mapv println errors)
        false))))

;; legacy to build one build
(defn autobuild [src-dirs build-options figwheel-options]
  (system/start-figwheel!
   {:all-builds [{:id "main-build"
                  :source-paths src-dirs
                  :build-options build-options}]
    :build-ids ["main-build"]
    :figwheel-options figwheel-options}))

