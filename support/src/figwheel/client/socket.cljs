(ns figwheel.client.socket
  (:require
   [cljs.reader :refer [read-string]]))

(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

(defn have-websockets? [] (js*  "(\"WebSocket\" in window)"))

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

(defn proper-build-id [build-id msg]
  (or (nil? build-id)
      (nil? (:build-id msg))
      (= (name build-id)
         (:build-id msg))))

(defn open [{:keys [retry-count retried-count websocket-url build-id] :as opts}]
  (if-not (have-websockets?)
    (.debug js/console "Figwheel: Can't start Figwheel!! This browser doesn't support WebSockets")
    (do
      (.debug js/console "Figwheel: trying to open cljs reload socket")
      (let [socket (js/WebSocket. websocket-url)]
        (set! (.-onmessage socket) (fn [msg-str]
                                     (when-let [msg (read-string (.-data msg-str))]
                                       #_(.log js/console (prn-str msg))
                                       (and (map? msg)
                                            (:msg-name msg)
                                            ;; don't forward pings
                                            (not= (:msg-name msg) :ping)
                                            (proper-build-id build-id msg)
                                            (swap! message-history-atom
                                                   conj msg)))))
        (set! (.-onopen socket)  (fn [x]
                                   (reset! socket-atom socket)
                                   (.debug js/console "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (let [retried-count (or retried-count 0)]
                                     (log opts "Figwheel: socket closed or failed to open")
                                     (when (> retry-count retried-count)
                                       (.setTimeout js/window
                                                    (fn []
                                                      (open
                                                       (assoc opts :retried-count (inc retried-count))))
                                                    ;; linear back off
                                                    (min 10000 (+ 2000 (* 500 retried-count))))))))
        (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))
        socket))))
