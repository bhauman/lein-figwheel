(ns figwheel-sidecar.protocols)

(defprotocol ChannelServer
  (-send-message [this channel-id msg-data callback])
  (-connection-data [this]))