(ns example-admin.core
  (:require
   [example.core]
   [figwheel.client :as fw]))

(fw/start { :build-id "example-admin"
            :debug true})
