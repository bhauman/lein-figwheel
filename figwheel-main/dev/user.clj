(ns user
  (:require [figwheel.server.ring]
            [figwheel.main.schema.config]))

(defn build-option-docs []
  (figwheel.main.schema.core/output-docs "doc/figwheel-main-options.md"))
