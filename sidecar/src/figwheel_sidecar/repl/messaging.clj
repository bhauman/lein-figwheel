(ns figwheel-sidecar.repl.messaging
  (:require
    [figwheel-sidecar.protocols :as protocols]))

(defn send-message [figwheel-server command & [payload]]
  (protocols/-send-message figwheel-server
                           (:build-id figwheel-server)
                           (merge payload {:msg-name :repl-driver
                                           :command  command})
                           nil))

; -- messages ---------------------------------------------------------------------------------------------------------------

(defn announce-job-start [figwheel-server request-id]
  (send-message figwheel-server :job-start {:request-id request-id}))

(defn announce-job-end [figwheel-server request-id]
  (send-message figwheel-server :job-end {:request-id request-id}))

(defn announce-repl-ns [figwheel-server ns-as-string]
  {:pre [(string? ns-as-string)]}
  (send-message figwheel-server :repl-ns {:ns ns-as-string}))

(defn report-output [figwheel-server request-id kind content]
  (send-message figwheel-server :output {:request-id request-id
                                         :kind       kind
                                         :content    content}))
