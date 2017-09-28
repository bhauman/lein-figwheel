;; This namespace was created to add to the :preloads clojureScript
;; compile option. This will allow you to start the figwheel client with the
;; options that you supplied in :external-config > :figwheel/config
(ns figwheel.connect
  (:require [figwheel.client])
  (:require-macros [figwheel.env-config :refer [external-tooling-config]]))

(defn ^:export start []
  (let [config (external-tooling-config)]
    (figwheel.client/start config)
    (when (:devcards config)
      (js/devcards.core.start-devcard-ui!*))))
