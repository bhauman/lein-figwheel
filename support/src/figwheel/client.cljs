(ns figwheel.client
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
    (.addCallback (loader/load (add-cache-buster request-url) #js { :cleanupWhenDone true })
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

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice) (.getElementsByTagName js/document "link")))

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

(declare heads-up-config-options** clear-heads-up figwheel-websocket)

(defn ll [v] (.log js/console v) v)

(defmulti heads-up-event-dispatch (fn [dataset] (.-figwheelEvent dataset)))
(defmethod heads-up-event-dispatch :default [_]  {})

(defmethod heads-up-event-dispatch "file-selected" [dataset]
  (.send figwheel-websocket (pr-str {:figwheel-event "file-selected"
                                     :file-name (.-fileName dataset)
                                     :file-line (.-fileLine dataset)})))

(defmethod heads-up-event-dispatch "close-heads-up" [dataset] (clear-heads-up))

(defn ancestor-nodes [el]
  (iterate (fn [e] (.-parentNode e)) el))

(defn get-dataset [el]
  (first (keep (fn [x] (when (.. x -dataset -figwheelEvent) (.. x -dataset)))
               (take 4 (ancestor-nodes el)))))

(defn heads-up-onclick-handler [event]
  (let [dataset (get-dataset (.. event -target))]
    (.preventDefault event)
    (heads-up-event-dispatch dataset)))

(defn ensure-container []
  (let [cont-id "figwheel-heads-up-container"]
    (if-not (.querySelector js/document (str "#" cont-id))
      (let [el (node :div { :id cont-id
                           :style
                           (str "-webkit-transition: all 0.2s ease-in-out;"
                                "-moz-transition: all 0.2s ease-in-out;"
                                "-o-transition: all 0.2s ease-in-out;"
                                "transition: all 0.2s ease-in-out;"
                                "font-size: 13px;"
                                "background: url(https://s3.amazonaws.com/bhauman-blog-images/jira-logo-scaled.png) no-repeat 10px 10px;"
                                "border-top: 1px solid #f5f5f5;"
                                "box-shadow: 0px 0px 1px #aaaaaa;"
                                "line-height: 18px;"
                                "color: #333;"
                                "font-family: monospace;"
                                "padding: 0px 10px 0px 70px;"
                                "position: fixed;"
                                "bottom: 0px;"
                                "left: 0px;"
                                "height: 0px;"
                                "opacity: 0.0;"
                                "box-sizing: border-box;"
                                ) })]
        (set! (.-onclick el) heads-up-onclick-handler)
        (-> (.-body js/document)
            (.appendChild el)))
      (.getElementById js/document cont-id))))

(defn set-style [c st-map]
  (mapv
   (fn [[k v]]
     (aset (.-style c) (name k) v))
   st-map))

(defn close-link []
  (str "<a style=\""
      "float: right;"
      "font-size: 18px;"
      "text-decoration: none;"
      "text-align: right;"
      "width: 30px;"
      "height: 30px;"
      "color: rgba(84,84,84, 0.5);"
      "\" href=\"#\"  data-figwheel-event=\"close-heads-up\">"
      "x"
      "</a>"))

(defn display-heads-up [style msg]
  (go
   (let [c (ensure-container)]
     (set! (.-innerHTML c ) msg)
     (set-style c (merge {
                          :paddingTop "10px"
                          :paddingBottom "10px"
                          :width "100%"
                          :minHeight "47px"
                          :height "auto"
                          :opacity "1.0" }
                         style))
     (<! (timeout 400)))))

(defn heading [s]
  (str"<div style=\""
      "font-size: 26px;"
      "line-height: 26px;"
      "margin-bottom: 2px;"
      "padding-top: 1px;"      
      "\">"
      s "</div>"))

(defn file-and-line-number [msg]
  (when (re-matches #".*at\sline.*" msg)
    (take 2 (reverse (string/split msg " ")))))

(defn file-selector-div [file-name line-number msg]
  (str "<div data-figwheel-event=\"file-selected\" data-file-name=\""
       file-name "\" data-file-line=\"" line-number
       "\">" msg "</div>"))

(defn format-line [msg]
  (if-let [[f ln] (file-and-line-number msg)]
    (file-selector-div f ln msg)
    (str "<div>" msg "</div>")))

(declare format-messages)

(defn display-error [exception-data]
  (let [formatted-messages (format-messages exception-data)
        [file-name file-line] (first (keep file-and-line-number formatted-messages))
        msg (apply str (map #(str "<div>" % "</div>") (format-messages exception-data)))]
    (display-heads-up {:backgroundColor "rgba(255, 161, 161, 0.95)"}
                      (str (close-link) (heading "Compile Error") (file-selector-div file-name file-line msg)))))

(defn display-warning [msg]
  (display-heads-up {:backgroundColor "rgba(255, 220, 110, 0.95)" }
                    (str (close-link) (heading "Compile Warning") (format-line msg))))

(defn heads-up-append-message [message]
  (let [c (ensure-container)]
     (set! (.-innerHTML c ) (str (.-innerHTML c) (format-line message)))))

(defn clear-heads-up []
  (go
   (let [c (ensure-container)]
     (set-style c { :opacity "0.0" })
     (<! (timeout 300))
     (set-style c { :width "auto"
                    :height "0px"
                    :padding "0px 10px 0px 70px"
                    :borderRadius "0px"
                    :backgroundColor "transparent" })
     (<! (timeout 200))
     (set! (.-innerHTML c ) ""))))

(defn display-loaded-start []
  (display-heads-up {:backgroundColor "rgba(211,234,172,1.0)"
                     :width "67px"
                     :height "67px"                     
                     :paddingLeft "0px"
                     :paddingRight "0px"
                     :borderRadius "35px" } ""))

(defn flash-loaded []
  (go
   (<! (display-loaded-start))
   (<! (timeout 400))
   (<! (clear-heads-up))))

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

(defn open-websocket [{:keys [retry-count retried-count websocket-url] :as opts}
                      msg-hist-atom]
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
                                            ;; don't forward pings
                                            (not= (:msg-name msg) :ping)
                                            (swap! msg-hist-atom conj msg)))))
        (set! (.-onopen socket)  (fn [x]
                                   (set! figwheel-websocket socket)
                                   (patch-goog-base)
                                   (.debug js/console "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (let [retried-count (or retried-count 0)]
                                     (log opts "Figwheel: socket closed or failed to open")
                                     (when (> retry-count retried-count)
                                       (.setTimeout js/window
                                                    (fn []
                                                      (open-websocket
                                                       (assoc opts :retried-count (inc retried-count))
                                                       msg-hist-atom))
                                                    ;; linear back off
                                                    (min 10000 (+ 2000 (* 500 retried-count))))))))
        (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))
        socket))))

;; if you don't configure a :jsload-callback or an :on-jsload callback
;; this function will dispatch a browser event
;;
;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.js-reload", function (e) { console.log(e.detail);} );

(defn default-on-jsload [url]
  (when (have-custom-events?)
    (.dispatchEvent (.-body js/document)
                    (js/CustomEvent. "figwheel.js-reload"
                                     (js-obj "detail" url)))))

;; exception formatting

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

(defn reload-js-files [{:keys [before-jsload on-jsload] :as opts} {:keys [files] :as msg}]
  (go
   (before-jsload files)
   (let [files'  (add-request-urls opts files) 
         res     (<! (load-all-js-files files'))]
     (when (not-empty res)
       (.debug js/console "Figwheel: loaded these files")
       (.log js/console (pr-str (map :file res)))
       (js/setTimeout #(apply on-jsload [res]) 10)))))

(defn default-on-compile-warning [{:keys [message] :as w}]
  (.warn js/console "Figwheel: Compile Warning -" message)
  w)

(defn default-before-load [files]
  (.debug js/console "Figwheel: loading files")
  files)

(defn default-on-cssload [files]
  (.debug js/console "Figwheel: loaded CSS files")
  (.log js/console (pr-str (map :file files)))
  files)

;; more flexible state management

(defn focus-msgs [name-set msg-hist]
  (cons (first msg-hist) (filter (comp name-set :msg-name) (rest msg-hist))))

(defn reload-file?* [msg-name opts]
  (or (:load-warninged-code opts)
      (not= msg-name :compile-warning)))

(defn reload-file-state? [msg-names opts]
  (and (= (first msg-names) :files-changed)
       (reload-file?* (second msg-names) opts)))

(defn block-reload-file-state? [msg-names opts]
  (and (= (first msg-names) :files-changed)
       (not (reload-file?* (second msg-names) opts))))

(defn warning-append-state? [msg-names]
  (= [:compile-warning :compile-warning] (take 2 msg-names)))

(defn warning-state? [msg-names]
  (= :compile-warning (first msg-names)))

(defn rewarning-state? [msg-names]
  (= [:compile-warning :files-changed :compile-warning] (take 3 msg-names)))

(defn compile-fail-state? [msg-names]
  (= :compile-failed (first msg-names)))

(defn compile-refail-state? [msg-names]
  (= [:compile-failed :compile-failed] (take 2 msg-names)))

(defn css-loaded-state? [msg-names]
  (= :css-files-changed (first msg-names)))

(defn file-reloader-plugin [opts]
  (let [ch (chan)]
    (go-loop []
             (when-let [msg-hist' (<! ch)]
               (let [msg-hist (focus-msgs #{:files-changed :compile-warning} msg-hist')
                     msg-names (map :msg-name msg-hist)
                     msg (first msg-hist)]
                 (cond
                  (reload-file-state? msg-names opts)
                  (<! (reload-js-files opts msg))

                  (block-reload-file-state? msg-names opts)
                  (.warn js/console "Figwheel: Not loading code with warnings - " (-> msg :files first :file)))
                 (recur))))
    (fn [msg-hist] (put! ch msg-hist) msg-hist)))


(defn css-reloader-plugin [opts]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (when (= msg-name :css-files-changed)
      (reload-css-files opts msg))))

(defn compile-fail-warning-plugin [{:keys [on-compile-warning on-compile-fail]}]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (condp = msg-name
          :compile-warning (on-compile-warning msg)
          :compile-failed  (on-compile-fail msg)
          nil)))

(defn heads-up-plugin-msg-handler [opts msg-hist']
  (let [msg-hist (focus-msgs #{:files-changed :compile-warning :compile-failed} msg-hist')
        msg-names (map :msg-name msg-hist)
        msg (first msg-hist)]
    (go
     (cond
      (reload-file-state? msg-names opts)
      (<! (flash-loaded))
      
      (compile-refail-state? msg-names)
      (do
        (<! (clear-heads-up))
        (<! (display-error (:exception-data msg))))
      
      (compile-fail-state? msg-names)
      (<! (display-error (:exception-data msg)))
      
      (warning-append-state? msg-names)
      (heads-up-append-message (:message msg))
      
      (rewarning-state? msg-names)
      (do
        (<! (clear-heads-up))
        (<! (display-warning (:message msg))))
      
      (warning-state? msg-names)
      (<! (display-warning (:message msg)))
      
      (css-loaded-state? msg-names)
      (<! (flash-loaded))))))

(defn heads-up-plugin [opts]
  (let [ch (chan)]
    (def heads-up-config-options** opts)
    (go-loop []
             (when-let [msg-hist' (<! ch)]
               (<! (heads-up-plugin-msg-handler opts msg-hist'))
               (recur)))
    (ensure-container)
    (fn [msg-hist] (put! ch msg-hist) msg-hist)))

(defonce config-defaults
  {:retry-count 100
   :websocket-url (str "ws://" js/location.host "/figwheel-ws")
   :load-warninged-code false
   
   :on-jsload default-on-jsload
   :before-jsload default-before-load
   :url-rewriter identity

   :on-cssload default-on-cssload
   
   :on-compile-fail default-on-compile-fail
   :on-compile-warning default-on-compile-warning
   
   :heads-up-display true })

(defn handle-deprecated-jsload-callback [config]
  (if (:jsload-callback config)
    (-> config
        (assoc  :on-jsload (:jsload-callback config))
        (dissoc :jsload-callback))
    config))

(defn base-plugins [system-options]
  (let [base {:file-reloader-plugin     file-reloader-plugin
              :comp-fail-warning-plugin compile-fail-warning-plugin
              :css-reloader-plugin      css-reloader-plugin}]
    (if (:heads-up-display system-options)
      (assoc base :heads-up-display-plugin heads-up-plugin)
      base)))

(defn start
  ([opts]
     (defonce __figwheel-start-once__
       (let [plugins' (:plugins opts) ;; plugins replaces all plugins
             merge-plugins (:merge-plugins opts) ;; merges plugins
             system-options (handle-deprecated-jsload-callback
                             (merge config-defaults
                                    (dissoc opts :plugins :merge-plugins)))
             plugins  (if plugins'
                        plugins'
                        (merge (base-plugins system-options) merge-plugins))
             msg-hist-atom (atom (list))]
         (doseq [[k plugin] plugins]
           (when plugin
             (let [pl (plugin system-options)]
               (add-watch msg-hist-atom k (fn [_ _ _ msg-hist] (pl msg-hist))))))
         (open-websocket system-options msg-hist-atom))))
  ([] (start {})))

;; legacy interface
(def watch-and-reload-with-opts start)
(defn watch-and-reload [& {:keys [] :as opts}] (start opts))

