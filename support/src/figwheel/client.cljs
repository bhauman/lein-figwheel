(ns figwheel.client
  (:require
   [goog.Uri :as guri]
   [goog.userAgent.product :as product]
   [goog.object :as gobj]
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [figwheel.client.socket :as socket]
   [figwheel.client.utils :as utils]   
   [figwheel.client.heads-up :as heads-up]
   [figwheel.client.file-reloading :as reloading]
   [clojure.string :as string]
   ;; to support repl doc
   [cljs.repl])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog]))

(def _figwheel-version_ "0.5.4-6")

;; exception formatting

(defn figwheel-repl-print
  ([stream args]
   (socket/send! {:figwheel-event "callback"
                  :callback-name "figwheel-repl-print"
                  :content {:stream stream
                            :args args}})
   nil)
  ([args]
   (figwheel-repl-print :out args)))

(defn console-out-print [args]
  (.apply (.-log js/console) js/console (into-array args)))

(defn console-err-print [args]
  (.apply (.-error js/console) js/console (into-array args)))

(defn repl-out-print-fn [& args]
  (console-out-print args)
  (figwheel-repl-print :out args)
  nil)

(defn repl-err-print-fn [& args]
  (console-err-print args)
  (figwheel-repl-print :err args)
  nil)

(defn enable-repl-print! []
  (set! *print-newline* false)
  (set-print-fn! repl-out-print-fn)
  (set-print-err-fn! repl-err-print-fn)  
  nil)

(def autoload?
  (if (utils/html-env?)
    (fn []
      (condp = (or (try
                     (when (js* "typeof localstorage !== 'undefined'")
                       (.getItem js/localStorage "figwheel_autoload"))
                     (catch js/Error e
                       false))
                   "true")
        "true" true
        "false" false))
    (fn [] true)))

(defn ^:export toggle-autoload []
  (when (utils/html-env?)
    (try
      (when (js* "typeof localstorage !== 'undefined'")
        (.setItem js/localStorage "figwheel_autoload" (not (autoload?))))
      (utils/log :info
                 (str "Figwheel autoloading " (if (autoload?) "ON" "OFF")))
      (catch js/Error e
        (utils/log :info
                   (str "Unable to access localStorage"))))))

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
                 (if (autoload?)
                     (cond
                       (reload-file-state? msg-names opts)
                       (alts! [(reloading/reload-js-files opts msg) (timeout 1000)])
                       
                       (block-reload-file-state? msg-names opts)
                       (utils/log :warn (str "Figwheel: Not loading code with warnings - " (-> msg :files first :file))))
                     (do
                       (utils/log :warn "Figwheel: code autoloading is OFF")
                       (utils/log :info (str "Not loading: " (map :file (:files msg))))))
                 (recur))))
    (fn [msg-hist] (put! ch msg-hist) msg-hist)))

#_(defn error-test2 []
  js/joe)

#_(defn error-test3 []
  (error-test2))

#_(defn error-test []
   (error-test3))

(defn truncate-stack-trace [stack-str]
  (take-while #(not (re-matches #".*eval_javascript_STAR__STAR_.*" %))
              (string/split-lines stack-str)))

(defn get-ua-product []
  (cond
    (utils/node-env?) :chrome
    product/SAFARI    :safari
    product/CHROME    :chrome
    product/FIREFOX   :firefox
    product/IE        :ie))

(let [base-path (utils/base-url-path)]
  (defn eval-javascript** [code opts result-handler]
    (try
      (enable-repl-print!)
      (let [result-value (utils/eval-helper code opts)]
        (result-handler
         {:status :success,
          :ua-product (get-ua-product)
          :value result-value}))
      (catch js/Error e
        (result-handler
         {:status :exception
          :value (pr-str e)
          :ua-product (get-ua-product)          
          :stacktrace (string/join "\n" (truncate-stack-trace (.-stack e)))
          :base-path base-path }))
      (catch :default e
        (result-handler
         {:status :exception
          :ua-product (get-ua-product)          
          :value (pr-str e)
          :stacktrace "No stacktrace available."}))
      (finally
        ;; should we let people shoot themselves in the foot?
        ;; you can theoretically disable repl printing in the repl
        ;; but for now I'm going to prevent it
        (enable-repl-print!)))))

(defn ensure-cljs-user
  "The REPL can disconnect and reconnect lets ensure cljs.user exists at least."
  []
  ;; this should be included in the REPL
  (when-not js/cljs.user
    (set! js/cljs.user #js {})))

(defn repl-plugin [{:keys [build-id] :as opts}]
  (fn [[{:keys [msg-name] :as msg} & _]]
    (when (= :repl-eval msg-name)
      (ensure-cljs-user)
      (eval-javascript** (:code msg) opts
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
      (if (and (autoload?)
               (:autoload opts))
        (<! (heads-up/flash-loaded))
        (<! (heads-up/clear)))
     
      (compile-refail-state? msg-names)
      (do
        (<! (heads-up/clear))
        (<! (heads-up/display-exception (:exception-data msg))))
      
      (compile-fail-state? msg-names)
      (<! (heads-up/display-exception (:exception-data msg)))
      
      (warning-append-state? msg-names)
      (heads-up/append-warning-message (:message msg))
      
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
         (heads-up/display-system-warning
          "Connection from different project"
          "Shutting connection down!!!!!"))))))

(defn enforce-figwheel-version-plugin [opts]
  (fn [msg-hist]
    (when-let [figwheel-version (-> msg-hist first :figwheel-version)]
      (when (not= figwheel-version _figwheel-version_)
        (socket/close!)
        (.error js/console "Figwheel: message received from different version of Figwheel.")
        (when (:heads-up-display opts)
          (go
            (<! (timeout 2000))
            (heads-up/display-system-warning
             "Figwheel Client and Server have different versions!!"
             (str "Figwheel Client Version \"" _figwheel-version_ "\" is not equal to "
                  "Figwheel Sidecar Version \"" figwheel-version "\""
                  ".  Shutting down Websocket Connection!"))))
        ))))

#_((enforce-figwheel-version-plugin {:heads-up-display true}) [{:figwheel-version "yeah"}])

;; defaults and configuration

;; default callbacks

;; if you don't configure a :jsload-callback or an :on-jsload callback
;; this function will dispatch a browser event
;;
;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.js-reload", function (e) { console.log(e.detail);} );

(def default-on-jsload identity)

(defn file-line-column [{:keys [file line column]}]
  (cond-> ""
    file (str "file " file)
    line (str " at line " line)
    (and line column) (str ", column " column)))

(defn default-on-compile-fail [{:keys [formatted-exception exception-data cause] :as ed}]
  (utils/log :debug "Figwheel: Compile Exception")
  (doseq [msg (format-messages exception-data)]
    (utils/log :info msg))
  (if cause
    (utils/log :info (str "Error on " (file-line-column ed))))
  ed)

(defn default-on-compile-warning [{:keys [message] :as w}]
  (utils/log :warn (str "Figwheel: Compile Warning - " (:message message) " in " (file-line-column message)))
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

   ;; :on-message identity
   
   :on-jsload default-on-jsload
   :before-jsload default-before-load

   :on-cssload default-on-cssload
   
   :on-compile-fail #'default-on-compile-fail
   :on-compile-warning #'default-on-compile-warning

   :reload-dependents true
   
   :autoload true
   
   :debug false
   
   :heads-up-display true

   :eval-fn false
   })

(defn handle-deprecated-jsload-callback [config]
  (if (:jsload-callback config)
    (-> config
        (assoc  :on-jsload (:jsload-callback config))
        (dissoc :jsload-callback))
    config))

(defn fill-url-template [config]
  (if (utils/html-env?)
      (update-in config [:websocket-url]
             (fn [x]
               (-> x
                   (string/replace "[[client-hostname]]" js/location.hostname)
                   (string/replace "[[client-port]]" js/location.port))))
      config))

(defn base-plugins [system-options]
  (let [base {:enforce-project-plugin enforce-project-plugin
              :enforce-figwheel-version-plugin enforce-figwheel-version-plugin
              :file-reloader-plugin     file-reloader-plugin
              :comp-fail-warning-plugin compile-fail-warning-plugin
              :css-reloader-plugin      css-reloader-plugin
              :repl-plugin      repl-plugin}
        base  (if (not (utils/html-env?)) ;; we are in an html environment?
               (select-keys base [#_:enforce-project-plugin
                                  :file-reloader-plugin
                                  :comp-fail-warning-plugin
                                  :repl-plugin])
               base)
        base (if (false? (:autoload system-options))
               (dissoc base :file-reloader-plugin)
               base)]
    (if (and (:heads-up-display system-options)
             (utils/html-env?))
      (assoc base :heads-up-display-plugin heads-up-plugin)
      base)))

(defn add-message-watch [key callback]
  (add-watch
   socket/message-history-atom key
   (fn [_ _ _ msg-hist] (callback (first msg-hist)))))

(defn add-plugins [plugins system-options]
  (doseq [[k plugin] plugins]
    (when plugin
      (let [pl (plugin system-options)]
        (add-watch socket/message-history-atom k
                   (fn [_ _ _ msg-hist] (pl msg-hist)))))))

(defn start
  ([opts]
   (when-not (nil? goog/dependencies_)
       (defonce __figwheel-start-once__
         (js/setTimeout
          #(let [plugins' (:plugins opts) ;; plugins replaces all plugins
                 merge-plugins (:merge-plugins opts) ;; merges plugins
                 system-options (-> config-defaults
                                    (merge (dissoc opts :plugins :merge-plugins))
                                    handle-deprecated-jsload-callback
                                    fill-url-template)
                 plugins  (if plugins'
                            plugins'
                            (merge (base-plugins system-options) merge-plugins))]
             (set! utils/*print-debug* (:debug opts))
             (enable-repl-print!)         
             (add-plugins plugins system-options)
             (reloading/patch-goog-base)
             (doseq [msg (:initial-messages system-options)]
               (socket/handle-incoming-message msg))
             (socket/open system-options))))))
  ([] (start {})))

;; legacy interface
(def watch-and-reload-with-opts start)
(defn watch-and-reload [& {:keys [] :as opts}] (start opts))


;; --- Bad Initial Compilation Helper Application ---
;;
;; this is only used to replace a missing compile target
;; when the initial compile fails due an exception
;; this is intended to be compiled seperately

(defn fetch-data-from-env []
  (try
    (read-string (gobj/get js/window "FIGWHEEL_CLIENT_CONFIGURATION"))
    (catch js/Error e
      (cljs.core/*print-err-fn*
       "Unable to load FIGWHEEL_CLIENT_CONFIGURATION from the environment")
      {:autoload false})))

(def console-intro-message
"Figwheel has compiled a temporary helper application to your :output-file.

The code currently in your configured output file does not
represent the code that you are trying to compile.

This temporary application is intended to help you continue to get
feedback from Figwheel until the build you are working on compiles
correctly.

When your ClojureScript source code compiles correctly this helper
application will auto-reload and pick up your freshly compiled
ClojureScript program.")

(defn bad-compile-helper-app []
  (enable-console-print!)
  (let [config (fetch-data-from-env)]
    (println console-intro-message)
    (heads-up/bad-compile-screen)
    (when-not js/goog.dependencies_
      (set! js/goog.dependencies_ true))
    (start config)
    (add-message-watch
     :listen-for-successful-compile
     (fn [{:keys [msg-name]}]
       (when (= msg-name :files-changed)
         (set! js/location.href js/location.href))))))
