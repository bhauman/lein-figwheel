(ns example.client
  (:require
   [goog.Uri :as guri]
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [clojure.string :as string])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [figwheel.client :refer [defonce]]))


(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

(defn reload-host [{:keys [websocket-url]}]
  (-> websocket-url
      (string/replace-first #"^wss?:" "")
      (string/replace-first #"^//" "")
      (string/split #"/")
      first))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

(defn js-reload [{:keys [request-url namespace dependency-file] :as msg} callback]
  (if (or dependency-file
            ;; IMPORTANT make sure this file is currently provided
          (.isProvided_ js/goog namespace))
    (.addCallback (loader/load (add-cache-buster request-url))
                  #(apply callback [msg]))
    (apply callback [msg])))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js-reload file-msg (fn [url] (put! out url) (close! out)))
    out))

(defn load-all-js-files [files]
  "Returns a chanel with one collection of loaded filenames on it."
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as d}]
  (->> file
       (str "//" (reload-host opts))
       url-rewriter
       (assoc d :request-url)))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn reload-js-files [{:keys [before-jsload on-jsload] :as opts} last-message-type {:keys [files] :as msg}]
  (if (or (:load-warninged-code opts)
          (not= last-message-type :compile-warning)) 
    (go
     (before-jsload files)
     (let [files'  (add-request-urls opts files) 
           res     (<! (load-all-js-files files'))]
       (when (not-empty res)
         (.debug js/console "Figwheel: loaded these files")
         (.log js/console (pr-str (map :file res)))
         (<! (timeout 10)) ;; wait a beat before callback
         (apply on-jsload [res]))))    
    (go
     (.warn js/console "Figwheel: Not loading code with warnings - " (-> msg :files first :file))
     0)))

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice) (.query js/document "link")))

(defn truncate-url [url]
  (-> (first (string/split url #"\?")) 
      (string/replace-first (str (.-protocol js/location) "//") "")
      (string/replace-first "http://" "")
      (string/replace-first "https://" "")      
      (string/replace-first #"^//" "")         
      (string/replace-first #"[^\/]*" "")))

(defn matches-file? [{:keys [file request-url]} link-href]
  (let [trunc-href (truncate-url link-href)]
    (or (= file trunc-href)
        (= (truncate-url request-url) trunc-href))))

(defn get-correct-link [f-data]
  (some (fn [l]
          (when (matches-file? f-data (.-href l)) l))
        (current-links)))

(defn clone-link [link url]
  (let [clone (.createElement js/document "link")]
    (set! (.-rel clone)      "stylesheet")
    (set! (.-media clone)    (.-media link))
    (set! (.-disabled clone) (.-disabled link))
    (set! (.-href clone)     (add-cache-buster url))
    clone))

(defn create-link [url]
  (let [link (.createElement js/document "link")]
    (set! (.-rel link)      "stylesheet")
    (set! (.-href link)     (add-cache-buster url))
    link))

(defn add-link-to-doc
  ([new-link]
     (.appendChild (aget (.getElementsByTagName js/document "head") 0)
                   new-link))
  ([orig-link klone]
     (let [parent (.-parentNode orig-link)]
       (if (= orig-link (.-lastChild parent))
         (.appendChild parent klone)
         (.insertBefore parent klone (.-nextSibling orig-link)))
       (go
        (<! (timeout 200))
        (.removeChild parent orig-link)))))

(defn reload-css-file [{:keys [file request-url] :as f-data}]
  (if-let [link (get-correct-link f-data)]
    (add-link-to-doc link (clone-link link request-url))
    (add-link-to-doc (create-link request-url))))

(defn reload-css-files [{:keys [on-cssload] :as opts} files-msg]
  (doseq [f (add-request-urls opts (:files files-msg))]
    (reload-css-file f))
  (go
   (<! (timeout 100))
   (on-cssload (:files files-msg))))

;; heads up display

;; cheap hiccup
(defn node [t attrs & children]
     (let [e (.createElement js/document (name t))]
       (doseq [k (keys attrs)] (.setAttribute e (name k) (get attrs k)))
       (doseq [ch children] (.appendChild e ch)) ;; children
       e))

(defn ensure-container [id]
  (if-not (.querySelector js/document (str "#" id))
    (-> (.getElementsByTagName js/document "body")
        (aget 0)
        (.appendChild (node :div { :id id
                                    :style
                                    (str "-webkit-transition: all 0.2s ease-in-out;"
                                         "-moz-transition: all 0.2s ease-in-out;"
                                         "-o-transition: all 0.2s ease-in-out;"
                                         "transition: all 0.2s ease-in-out;"
                                         "font-size: 14px;"
                                         "line-height: 18px;"
                                         "text-align: center;"
                                         "color: white;"
                                         "font-family: monospace;"
                                         "padding: 0px 30px;"
                                         "position: fixed;"
                                         "bottom: 0px;"
                                         "right: 0px;"
                                         "left: 0px;"
                                         "height: 0px;"
                                         "opacity: 0.0;"
                                         ) })))
    (.getElementById js/document id)))

(defn set-style [c st-map]
  (mapv
   (fn [[k v]]
     (aset (.-style c) (name k) v))
   st-map))

(defn display-heads-up [style msg]
  (go
   (let [c (ensure-container "figwheel-heads-up-container")]
     (set! (.-innerHTML c ) msg)
     (set-style c (merge {
                          :paddingTop "10px"
                          :paddingBottom "10px"
                          :height "auto"
                          :opacity "1.0" }
                         style))
     (<! (timeout 400)))))

(defn display-error [msg]
  (display-heads-up {:backgroundColor "rgba(204,50,30, 0.95)"} msg))

(defn display-warning [msg]
  (display-heads-up {:backgroundColor "rgba(231, 154, 0, 0.95)" } msg))

(defn heads-up-append-message [message]
  (let [c (ensure-container "figwheel-heads-up-container")]
     (set! (.-innerHTML c ) (str (.-innerHTML c) "<br>" message))))

(defn clear-heads-up []
  (go
   (let [c (ensure-container "figwheel-heads-up-container")]
     (set-style c { :opacity "0.0" })
     (<! (timeout 200))
     (set! (.-innerHTML c ) "")
     (set-style c { :height "0px"
                    :backgroundColor "transparent" }))))

(ensure-container "figwheel-heads-up-container")
#_(warning-circle "This is an error yeppers")

;; super tricky hack to get goog to load newly required files

(defn figwheel-closure-import-script [src]
  (if (.inHtmlDocument_ js/goog)
    (do
      #_(.log js/console "Figwheel: latently loading required file " src )
      (loader/load (add-cache-buster src))
      true)
    false))

(defn patch-goog-base []
  (set! (.-provide js/goog) (.-exportPath_ js/goog))
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) figwheel-closure-import-script))

(defn have-websockets? [] (js*  "(\"WebSocket\" in window)"))
(defn have-custom-events? [] (js*  "(\"CustomEvent\" in window)"))

;; handle messages coming over the WebSocket
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

(defn watch-and-reload* [{:keys [retry-count websocket-url] :as opts}
                         msg-channel]
  (if-not (have-websockets?)
    (.debug js/console "Figwheel: Can't start Figwheel!! This browser doesn't support WebSockets")
    (do
      (.debug js/console "Figwheel: trying to open cljs reload socket")
      (let [socket (js/WebSocket. websocket-url)]
        (set! (.-onmessage socket) (fn [msg-str]
                                     (when-let [msg (read-string (.-data msg-str))]
                                       #_(.log js/console (pr-str msg))
                                       (and (map? msg)
                                            (:msg-name msg)
                                            (not= (:msg-name msg) :ping)
                                            (put! msg-channel msg)))))
        (set! (.-onopen socket)  (fn [x]
                                   (patch-goog-base)
                                   (.debug js/console "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (log opts "Figwheel: socket closed or failed to open")
                                   (when (> retry-count 0)
                                     (.setTimeout js/window
                                                  (fn []
                                                    (watch-and-reload*
                                                     (assoc opts :retry-count (dec retry-count))
                                                     msg-channel))
                                                  2000))))
        (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))
        socket))))

(defn default-on-jsload [url]
  (when (have-custom-events?)
    (.dispatchEvent (.querySelector js/document "body")
                    (js/CustomEvent. "figwheel.js-reload"
                                     (js-obj "detail" url)))))

(defn get-essential-messages [ed]
  (when ed
    (cons (select-keys ed [:message :class])
          (get-essential-messages (:cause ed)))))

(defn error-msg-format [{:keys [message class]}] (str class " : " message))

(def format-messages (comp (partial map error-msg-format) get-essential-messages))

(defn default-on-compile-fail [{:keys [formatted-exception exception-data] :as ed}]
  (.debug js/console "Figwheel: Compile Exception")
  (doseq [msg (format-messages exception-data)]
    (.log js/console msg))
  ed)

;; compile failure behavior

(defn compile-failed [opts
                      {:keys [formatted-exception exception-data] :as fail-msg}
                      compile-fail-callback]
  (compile-fail-callback (dissoc fail-msg :msg-name))
  (display-error (clojure.string/join "<br>" (format-messages exception-data))))

(defn compile-warning [{:keys [on-compile-warning]} last-message-type
                       {:keys [message] :as msg}]
  (on-compile-warning msg)
  (if (= last-message-type :compile-warning)
    (go (heads-up-append-message message) 1)
    (display-warning message)))

(defn default-on-compile-warning [{:keys [message] :as w}]
  (.warn js/console "Figwheel: Compile Warning -" message)
  w)

(defn default-before-load [files]
  (.debug js/console "Figwheel: loading files")
  #_(.log js/console (pr-str (mapv :file files)))
  files)

(defn default-on-cssload [files]
  (.debug js/console "Figwheel: loaded CSS files")
  (.log js/console (pr-str (map :file files)))
  files)

(defn message-handler-loop [msg-channel {:keys [on-compile-warning on-compile-fail] :as opts}]
  (go-loop [last-message-type ::not-a-known-message]
           (let [msg (<! msg-channel)]
             (when msg
               (when (and (not= :compile-warning last-message-type)
                          (= (:msg-name msg) :files-changed))
                 (<! (clear-heads-up)))
               (condp = (:msg-name msg)
                 :files-changed     (<! (reload-js-files opts last-message-type msg) )
                 :css-files-changed (reload-css-files opts msg)
                 :compile-failed    (<! (compile-failed opts msg on-compile-fail))
                 :compile-warning   (<! (compile-warning opts last-message-type msg))
                 nil)
               (recur (:msg-name msg))))))

(defn watch-and-reload-with-opts [opts]
  (defonce watch-and-reload-singleton
    (let [msg-channel (chan)]
      (let [config (merge { :retry-count 100 
                           :jsload-callback default-on-jsload ;; *** deprecated
                           :on-jsload (or (:jsload-callback opts) default-on-jsload)
                           :on-cssload default-on-cssload
                           :before-jsload default-before-load
                           :on-compile-fail default-on-compile-fail
                           :on-compile-warning default-on-compile-warning
                           :url-rewriter identity
                           :websocket-url (str "ws://" js/location.host "/figwheel-ws")
                           :load-warninged-code false}
                          opts)]
        (watch-and-reload* config msg-channel)
        (message-handler-loop msg-channel config)))))

;; This takes keyword arguments
(defn watch-and-reload
  [& {:keys [] :as opts}]
  (watch-and-reload-with-opts opts))
