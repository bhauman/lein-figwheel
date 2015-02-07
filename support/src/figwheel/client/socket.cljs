(ns figwheel.client.socket
  (:require
   [figwheel.client.utils :as utils]
   [cljs.reader :refer [read-string]]))

(defn get-websocket-imp []
  (cond
    (utils/html-env?) (aget js/window "WebSocket")
    (utils/node-env?) (try (js/require "ws")
                           (catch js/Error e
                             nil))
    :else nil))

;; messages have the following formats

;; files-changed message
;; { :msg-name :files-changed
;;   :files    [{:file "/js/compiled/out/example/core.js",
;;               :type :javascript, 
;;               :msg-name :file-changed,
;;               :namespace "example.core" }] }

;; css-files-changed message
;; there should really only be one file in here at a time
;; { :msg-name :css-files-changed
;;   :files    [{:file "/css/example.css",
;;               :type :css }] }

;; compile-failed message
;; { :msg-name :compile-failed
;;   :exception-data {:cause { ... lots of exception info ... } }}
;; the exception data is nested raw info obtained for the compile time
;; exception

(defonce message-history-atom (atom (list)))

(defonce socket-atom (atom false))

(defn send!
  "Send a end message to the server."
  [msg]
  (when @socket-atom
    (.send @socket-atom (pr-str msg))))

(defn close! []
  (set! (.-onclose @socket-atom) identity)
  (.close @socket-atom))

(defn open [{:keys [retry-count retried-count websocket-url build-id] :as opts}]
  (if-let [WebSocket (get-websocket-imp)]
    (do
      (utils/log :debug "Figwheel: trying to open cljs reload socket")
      (let [url (str websocket-url (if build-id (str "/" build-id) ""))
            socket (WebSocket. url)]
        (set! (.-onmessage socket) (fn [msg-str]
                                     (when-let [msg (read-string (.-data msg-str))]
                                       (utils/debug-prn msg)
                                       (and (map? msg)
                                            (:msg-name msg)
                                            ;; don't forward pings
                                            (not= (:msg-name msg) :ping)
                                            (swap! message-history-atom
                                                   conj msg)))))
        (set! (.-onopen socket)  (fn [x]
                                   (reset! socket-atom socket)
                                   (utils/log :debug "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (let [retried-count (or retried-count 0)]
                                     (utils/debug-prn "Figwheel: socket closed or failed to open")
                                     (when (> retry-count retried-count)
                                       (js/setTimeout 
                                        (fn []
                                          (open
                                           (assoc opts :retried-count (inc retried-count))))
                                        ;; linear back off
                                        (min 10000 (+ 2000 (* 500 retried-count))))))))
        (set! (.-onerror socket) (fn [x] (utils/debug-prn "Figwheel: socket error ")))
        socket))
    (utils/log :debug
               (if (utils/node-env?)
                 "Figwheel: Can't start Figwheel!! Please make sure ws is installed\n do -> 'node install ws'"
                 "Figwheel: Can't start Figwheel!! This browser doesn't support WebSockets"))))
