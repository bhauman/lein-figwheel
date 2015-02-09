(ns example-admin.core
  (:require
   [figwheel.client :as fw]))

(fw/start { :build-id "example-admin"
            :debug true})
