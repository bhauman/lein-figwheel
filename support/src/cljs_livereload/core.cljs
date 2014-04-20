(ns cljs-livereload.core
  (:require
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]]))

(defn js-reload [url callback]
  (.log js/console "cljs-livereload: reloading javascript file " url)
  (let [deferred (loader/load url)]
    (.addCallback deferred  (fn [] (apply callback [url])))))

(defn watch-and-reload* [{:keys [retry-count websocket-url jsload-callback] :as opts}]
    (set! js/COMPILED true)
    (.log js/console "trying to open cljs reload socket")  
    (let [socket (js/WebSocket. websocket-url)]
      (set! (.-onmessage socket) (fn [msg-str]
                                   (let [msg (read-string (.-data msg-str))]
                                     (when (= (:msg-name msg) :file-changed)
                                       (js-reload (:file msg) jsload-callback)))))
      (set! (.-onopen socket)  (fn [x]
                                 (.log js/console "cljs-livereload: socket connection established")))
      (set! (.-onclose socket) (fn [x]
                                 (.log js/console "cljs-livereload: socket closed or failed to open")
                                 (when (> retry-count 0)
                                   (.setTimeout js/window
                                                (fn []
                                                  (watch-and-reload*
                                                   (assoc opts :retry-count (dec retry-count))))
                                                2000))))
      (set! (.-onerror socket) (fn [x] (.log js/console "cljs-livereload: socket error ")))))

(defn watch-and-reload [& {:keys [retry-count websocket-url jsload-callback] :as opts}]
  (watch-and-reload* (merge { :retry-count (or retry-count 100)
                              :jsload-callback (or jsload-callback
                                                  (fn [url]
                                                    (.dispatchEvent (.querySelector js/document "body")
                                                                    (js/CustomEvent. "cljs-livereload.js-reload" (js-obj "detail" url)))))
                              :websocket-url (or websocket-url "ws:localhost:8080/cljs-livereload-ws") }
                              opts)))
