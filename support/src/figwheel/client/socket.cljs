(ns figwheel.client.socket
  (:require
   [figwheel.client.utils :as utils]
   [cognitect.transit :as transit]))

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
(defonce socket-reader (transit/reader :json))
(defonce socket-writer (transit/writer :json))

(defn serialize-message [msg]
  (transit/write socket-writer msg))

(defn unserialize-message [payload]
  (transit/read socket-reader payload))

(defn connected? []
  (boolean @socket-atom))

(defn send!
  "Send a end message to the server."
  [msg]
  (when (connected?)
    (utils/debug-prn (pr-str msg))
    (.send @socket-atom (serialize-message msg))))

(defn clear-socket-atom []
  (reset! socket-atom false))

(defn close! []
  (set! (.-onclose @socket-atom) identity)
  (let [socket @socket-atom]
    (clear-socket-atom)
    (.close socket)))

(defn open [{:keys [retry-count retried-count websocket-url build-id] :as opts}]
  (if-let [WebSocket (get-websocket-imp)]
    (do
      (utils/log :debug "Figwheel: trying to open cljs reload socket")
      (let [url (str websocket-url (if build-id (str "/" build-id) ""))
            socket (WebSocket. url)]
        (set! (.-onmessage socket) (fn [msg-str]
                                     (when-let [msg (unserialize-message (.-data msg-str))]
                                       (utils/debug-prn msg)
                                       (and (map? msg)
                                            (:msg-name msg)
                                            ;; don't forward pings
                                            (not= (:msg-name msg) :ping)
                                            (swap! message-history-atom
                                                   conj msg)))))
        (set! (.-onopen socket)  (fn [x]
                                   (reset! socket-atom socket)
                                   (when (utils/html-env?)
                                     (.addEventListener js/window "beforeunload" close!))
                                   (utils/log :debug "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (clear-socket-atom) ; socket close can be triggered by someone external, not only via close!
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
                 "Figwheel: Can't start Figwheel!! Please make sure ws is installed\n do -> 'npm install ws'"
                 "Figwheel: Can't start Figwheel!! This browser doesn't support WebSockets"))))
