(ns example.dev
  (:require
   [figwheel.client :as fw]
   [example.core :as core])
  (:require-macros
   [figwheel.client.utils :refer [enable-dev-blocks!]]))

(enable-console-print!)

(enable-dev-blocks!)

(fw/start {
           :websocket-url "ws://localhost:3449/figwheel-ws"
           :build-id "example"
           ;; if you want to disable autoloading
           ;; :autoload false
           :debug true
           :on-jsload (fn []
                        ;; it can be helpful to touch the state on 
                        ;; reload to cause a re-render
                        (swap! core/app-state update-in [:__figwheel_counter] inc))})
