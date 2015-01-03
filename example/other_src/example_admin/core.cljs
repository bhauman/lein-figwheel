(ns example-admin.core
  (:require
   [figwheel.client :as fw]))

(fw/start)

(.log js/console "hello there now")
