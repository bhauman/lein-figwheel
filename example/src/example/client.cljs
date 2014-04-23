(ns example.client
  (:require
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]])
  (:require-macros
   [figwheel.client :refer [defonce]]))

(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

(defn js-reload [{:keys [file namespace dependency-file] :as msg} callback]
  (when (or dependency-file
            (.isProvided_ js/goog namespace)) ;; make sure that this
    (.log js/console "Figwheel: reloading the javascript file " file )
    (let [deferred (loader/load file)]
      (.addCallback deferred  (fn [] (apply callback [file]))))))

(defn figwheel-closure-import-script [src]
  (if (.inHtmlDocument_ js/goog)
    (do
      #_(.log js/console "Figwheel: latently loading required file " src )
      (loader/load src)
      true)
    false))

(defn patch-goog-base []
  ;; this is what makes all this magic possible
  (set! (.-provide js/goog) (.-exportPath_ js/goog))  
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) figwheel-closure-import-script))

(defn watch-and-reload* [{:keys [retry-count websocket-url jsload-callback] :as opts}]
    (.log js/console "Figwheel: trying to open cljs reload socket")  
    (let [socket (js/WebSocket. websocket-url)]
      (set! (.-onmessage socket) (fn [msg-str]
                                   (let [msg (read-string (.-data msg-str))]
                                     (when (= (:msg-name msg) :file-changed)
                                       (js-reload msg jsload-callback)))))
      (set! (.-onopen socket)  (fn [x]
                                 (patch-goog-base)
                                 (.log js/console "Figwheel: socket connection established")))
      (set! (.-onclose socket) (fn [x]
                                 (log opts "Figwheel: socket closed or failed to open")
                                 (when (> retry-count 0)
                                   (.setTimeout js/window
                                                (fn []
                                                  (watch-and-reload*
                                                   (assoc opts :retry-count (dec retry-count))))
                                                2000))))
      (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))))

(defn watch-and-reload [& {:keys [retry-count websocket-url jsload-callback] :as opts}]
  (defonce watch-and-reload-singleton
    (watch-and-reload* (merge { :retry-count 100 
                                :jsload-callback (fn [url]
                                                   (.dispatchEvent (.querySelector js/document "body")
                                                                   (js/CustomEvent. "figwheel.js-reload"
                                                                                    (js-obj "detail" url))))
                                :websocket-url "ws:localhost:8080/figwheel-ws" }
                              opts))))
