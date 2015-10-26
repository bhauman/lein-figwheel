(ns figwheel-sidecar.channel-server
  (:require [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [clojure.edn :as edn]))

(defprotocol ChannelServer
  (-send-message [this channel-id msg-data callback])
  (-connection-data [this]))

(defn send-message [figwheel-server channel-id msg-data]
  (-send-message figwheel-server channel-id msg-data nil))

(defn send-message-with-callback [figwheel-server channel-id msg-data callback]
  (-send-message figwheel-server channel-id msg-data callback))

(defn eval-js
  ([figwheel-server channel-id code]
   (eval-js figwheel-server channel-id code nil))
  ([figwheel-server channel-id code callback]
   (-send-message figwheel-server channel-id {:msg-name :repl-eval :code code} callback)))

(defn eval-cljs
  ([figwheel-server channel-id code]
   (eval-cljs figwheel-server channel-id code nil))
  ([figwheel-server channel-id code callback]
   (let [js-code (->> code
                   edn/read-string
                   (ana/analyze (ana/empty-env))
                   ana/no-warn
                   comp/emit
                   with-out-str)]
     (eval-js figwheel-server channel-id js-code))))

(defn connection-data [figwheel-server]
  (-connection-data figwheel-server))


