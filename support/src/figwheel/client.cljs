(ns figwheel.client
  (:require
   [goog.Uri :as guri]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [figwheel.client.socket :as socket]
   [figwheel.client.utils :as utils]   
   [figwheel.client.heads-up :as heads-up]
   [figwheel.client.file-reloading :as reloading]
   [clojure.string :as string]
   ;; to support repl doc
   [cljs.repl])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

;; exception formatting

(defn figwheel-repl-print [args]
  (socket/send! {:figwheel-event "callback"
                 :callback-name "figwheel-repl-print"
                 :content args})
  args)

(defn console-print [args]
  (.apply (.-log js/console) js/console (into-array args))
  args)

(defn enable-repl-print! []
  (set! *print-newline* false)
  (set! *print-fn*
        (fn [& args]
          (-> args
            console-print
            figwheel-repl-print))))

(defn get-essential-messages [ed]
  (when ed
    (cons (select-keys ed [:message :class])
          (get-essential-messages (:cause ed)))))

(defn error-msg-format [{:keys [message class]}] (str class " : " message))

(def format-messages (comp (partial map error-msg-format) get-essential-messages))

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
                 #_(.log js/console (prn-str msg))
                 (cond
                  (reload-file-state? msg-names opts)
                  (alts! [(reloading/reload-js-files opts msg) (timeout 1000)])
                  
                  (block-reload-file-state? msg-names opts)
                  (.warn js/console "Figwheel: Not loading code with warnings - " (-> msg :files first :file)))
                 (recur))))
    (fn [msg-hist] (put! ch msg-hist) msg-hist)))

(defn truncate-stack-trace [stack-str]
  (string/join "\n" (take-while #(not (re-matches #".*eval_javascript_STAR__STAR_.*" %))
                                (string/split-lines stack-str))))

(defn eval-javascript** [code result-handler]
  (try
    (binding [*print-fn* (fn [& args]
                           (-> args
                             console-print
                             figwheel-repl-print))
              *print-newline* false]
      (result-handler
       {:status :success,
        :value (str (js* "eval(~{code})"))}))
    (catch js/Error e
      (result-handler
       {:status :exception
        :value (pr-str e)
        :stacktrace (if (.hasOwnProperty e "stack")
                      (truncate-stack-trace (.-stack e)) 
                      "No stacktrace available.")}))
    (catch :default e
      (result-handler
       {:status :exception
        :value (pr-str e)
        :stacktrace "No stacktrace available."}))))

(defn ensure-cljs-user
  "The REPL can disconnect and reconnect lets ensure cljs.user exists at least."
  []
  (when-not js/cljs  
    (set! js/cljs #js {}))
  (when-not (.-user js/cljs)
    (set! (.-user js/cljs) #js {})))

(defn repl-plugin [{:keys [build-id] :as opts}]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (when (= :repl-eval msg-name)
      (ensure-cljs-user)
      (eval-javascript** (:code msg)
       (fn [res]
         (socket/send! {:figwheel-event "callback"
                        :callback-name (:callback-name  msg)
                        :content res}))))))

(defn css-reloader-plugin [opts]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (when (= msg-name :css-files-changed)
      (reloading/reload-css-files opts msg))))

(defn compile-fail-warning-plugin [{:keys [on-compile-warning on-compile-fail]}]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (condp = msg-name
          :compile-warning (on-compile-warning msg)
          :compile-failed  (on-compile-fail msg)
          nil)))

;; this is seperate for live dev only
(defn heads-up-plugin-msg-handler [opts msg-hist']
  (let [msg-hist (focus-msgs #{:files-changed :compile-warning :compile-failed} msg-hist')
        msg-names (map :msg-name msg-hist)
        msg (first msg-hist)]
    (go
     (cond
      (reload-file-state? msg-names opts)
      (<! (heads-up/flash-loaded))

      (compile-refail-state? msg-names)
      (do
        (<! (heads-up/clear))
        (<! (heads-up/display-error (format-messages (:exception-data msg)))))
      
      (compile-fail-state? msg-names)
      (<! (heads-up/display-error (format-messages (:exception-data msg))))
      
      (warning-append-state? msg-names)
      (heads-up/append-message (:message msg))
      
      (rewarning-state? msg-names)
      (do
        (<! (heads-up/clear))
        (<! (heads-up/display-warning (:message msg))))
      
      (warning-state? msg-names)
      (<! (heads-up/display-warning (:message msg)))
      
      (css-loaded-state? msg-names)
      (<! (heads-up/flash-loaded))))))

(defn heads-up-plugin [opts]
  (let [ch (chan)]
    (def heads-up-config-options** opts)
    (go-loop []
             (when-let [msg-hist' (<! ch)]
               (<! (heads-up-plugin-msg-handler opts msg-hist'))
               (recur)))
    (heads-up/ensure-container)
    (fn [msg-hist] (put! ch msg-hist) msg-hist)))

(defn enforce-project-plugin [opts]
  (fn [msg-hist]
    (when (< 1 (count (set (keep :project-id (take 5 msg-hist)))))
      (socket/close!)
      (.error js/console "Figwheel: message received from different project. Shutting socket down.")
      (when (:heads-up-display opts)
        (go
         (<! (timeout 3000))
         (heads-up/display-system-warning "Connection from different project"
                                          "Shutting connection down!!!!!"))))))

;; defaults and configuration

;; default callbacks

;; if you don't configure a :jsload-callback or an :on-jsload callback
;; this function will dispatch a browser event
;;
;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.js-reload", function (e) { console.log(e.detail);} );

(defn default-on-jsload [url]
  (when (and (utils/html-env?) (js*  "(\"CustomEvent\" in window)"))
    (.dispatchEvent (.-body js/document)
                    (js/CustomEvent. "figwheel.js-reload"
                                     (js-obj "detail" url)))))

(defn default-on-compile-fail [{:keys [formatted-exception exception-data] :as ed}]
  (utils/log :debug "Figwheel: Compile Exception")
  (doseq [msg (format-messages exception-data)]
    (utils/log :info msg))
  ed)

(defn default-on-compile-warning [{:keys [message] :as w}]
  (utils/log :warn (str "Figwheel: Compile Warning - " message))
  w)

(defn default-before-load [files]
  (utils/log :debug "Figwheel: notified of file changes")
  files)

(defn default-on-cssload [files]
  (utils/log :debug "Figwheel: loaded CSS files")
  (utils/log :info (pr-str (map :file files)))  
  files)

(defonce config-defaults
  {:retry-count 100
   :websocket-url (str "ws://"
                       (if (utils/html-env?) js/location.host "localhost:3449")
                       "/figwheel-ws")
   :load-warninged-code false
   
   :on-jsload default-on-jsload
   :before-jsload default-before-load

   :url-rewriter false

   :on-cssload default-on-cssload
   
   :on-compile-fail default-on-compile-fail
   :on-compile-warning default-on-compile-warning

   :debug false
   
   :heads-up-display true

   :load-unchanged-files true
   })

(defn handle-deprecated-jsload-callback [config]
  (if (:jsload-callback config)
    (-> config
        (assoc  :on-jsload (:jsload-callback config))
        (dissoc :jsload-callback))
    config))

(defn base-plugins [system-options]
  (let [base {:enforce-project-plugin enforce-project-plugin
              :file-reloader-plugin     file-reloader-plugin
              :comp-fail-warning-plugin compile-fail-warning-plugin
              :css-reloader-plugin      css-reloader-plugin
              :repl-plugin      repl-plugin}
       base  (if (not (.. js/goog inHtmlDocument_)) ;; we are in node?
               (select-keys base [#_:enforce-project-plugin
                                  :file-reloader-plugin
                                  :comp-fail-warning-plugin
                                  :repl-plugin])
               base)]
    (if (and (:heads-up-display system-options)
             (utils/html-env?))
      (assoc base :heads-up-display-plugin heads-up-plugin)
      base)))

(defn add-plugins [plugins system-options]
  (doseq [[k plugin] plugins]
    (when plugin
      (let [pl (plugin system-options)]
        (add-watch socket/message-history-atom k
                   (fn [_ _ _ msg-hist] (pl msg-hist)))))))

(defn start
  ([opts]
   (defonce __figwheel-start-once__
     (js/setTimeout
      #(let [plugins' (:plugins opts) ;; plugins replaces all plugins
             merge-plugins (:merge-plugins opts) ;; merges plugins
             system-options (handle-deprecated-jsload-callback
                             (merge config-defaults
                                    (dissoc opts :plugins :merge-plugins)))
             plugins  (if plugins'
                        plugins'
                        (merge (base-plugins system-options) merge-plugins))]
         (set! utils/*print-debug* (:debug opts))
         #_(enable-repl-print!)         
         (add-plugins plugins system-options)
         (reloading/patch-goog-base)
         (socket/open system-options)))))
  ([] (start {})))

;; legacy interface
(def watch-and-reload-with-opts start)
(defn watch-and-reload [& {:keys [] :as opts}] (start opts))
