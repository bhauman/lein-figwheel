(ns figwheel-sidecar.channel-server)

(defprotocol ChannelServer
  (-send-message [this channel-id msg-data callback])
  (-connection-data [this]))

(defn send-message [figwheel-server channel-id msg-data]
  (-send-message figwheel-server channel-id msg-data nil))

(defn send-message-with-callback [figwheel-server channel-id msg-data callback]
  (-send-message figwheel-server channel-id msg-data callback))

(defn connection-data [figwheel-server]
  (-connection-data figwheel-server))


