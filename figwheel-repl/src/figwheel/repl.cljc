(ns figwheel.repl
  (:require
   [clojure.string :as string]
   #?@(:cljs [[goog.object :as gobj]
              [goog.storage.mechanism.mechanismfactory :as storage-factory]
              [goog.Uri :as guri]
              [goog.string :as gstring]
              [goog.net.jsloader :as loader]
              [goog.net.XhrIo :as xhrio]
              [goog.log :as glog]
              [goog.html.legacyconversions :as conv]
              [goog.userAgent.product :as product]]
       :clj [[clojure.data.json :as json]
             [clojure.set :as set]
             [clojure.edn :as edn]
             [clojure.java.browse :as browse]
             [cljs.repl]
             [cljs.stacktrace]
             [clojure.string :as string]]))
  (:import
   #?@(:cljs [goog.net.WebSocket
              goog.debug.Console
              [goog.Uri QueryData]
              [goog Promise]
              [goog.storage.mechanism HTML5SessionStorage]]
       :clj [java.util.concurrent.ArrayBlockingQueue])))

#?(:cljs (do

;; TODO dev only
(enable-console-print!)

;; --------------------------------------------------
;; Logging
;; --------------------------------------------------
;;
;; Levels
;; goog.debug.Logger.Level.(SEVERE WARNING INFO CONFIG FINE FINER FINEST)
;;
;; set level (.setLevel logger goog.debug.Logger.Level.INFO)
;; disable   (.setCapturing log-console false)

(defonce logger (glog/getLogger "Figwheel REPL"))
(defonce log-console (let [c (goog.debug.Console.)]
                       ;; don't display time
                       (doto (.getFormatter c)
                         (gobj/set "showAbsoluteTime" false)
                         (gobj/set "showRelativeTime" false))
                       c))
(defonce init-logger (do (.setCapturing log-console true) true))
(defn debug [msg]
  (glog/log logger goog.debug.Logger.Level.FINEST msg))

;; dev
(.setLevel logger goog.debug.Logger.Level.FINE #_ST )

;; --------------------------------------------------------------
;; Bootstrap goog require reloading
;; --------------------------------------------------------------

(declare queued-file-reload)

(defn unprovide! [ns]
  (let [path (gobj/get js/goog.dependencies_.nameToPath ns)]
    (gobj/remove js/goog.dependencies_.visited path)
    (gobj/remove js/goog.dependencies_.written path)
    (gobj/remove js/goog.dependencies_.written (str js/goog.basePath path))))

;; this will not work unless bootstrap has been called
(defn figwheel-require [src reload]
  ;; require is going to be called
  (set! (.-require js/goog) figwheel-require)
  (when (= reload "reload-all")
    (set! (.-cljsReloadAll_ js/goog) true))
  (when (or reload (.-cljsReloadAll_ js/goog))
    (unprovide! src))
  (let [res (.require_figwheel_backup_ js/goog src)]
    (when (= reload "reload-all")
      (set! (.-cljsReloadAll_ js/goog) false))
    res))

(defn bootstrap-goog-base
  "Reusable browser REPL bootstrapping. Patches the essential functions
  in goog.base to support re-loading of namespaces after page load."
  []
  ;; The biggest problem here is that clojure.browser.repl might have
  ;; patched this or might patch this afterward
  (when-not js/COMPILED
    (when-not (.-require_figwheel_backup_ js/goog)
      (set! (.-require_figwheel_backup_ js/goog) (or js/goog.require__ js/goog.require)))
    (set! (.-isProvided_ js/goog) (fn [name] false))
    (when-not (and (exists? js/cljs)
                   (exists? js/cljs.user))
      (goog/constructNamespace_ "cljs.user"))
    (set! (.-CLOSURE_IMPORT_SCRIPT goog/global) queued-file-reload)
    (set! (.-require js/goog) figwheel-require)))

(defn patch-goog-base []
  (defonce bootstrapped-cljs (do (bootstrap-goog-base) true)))

;; --------------------------------------------------------------
;; File reloading on different platforms
;; --------------------------------------------------------------

;; this assumes no query string on url
(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

(def gloader
  (cond
    (exists? loader/safeLoad)
    #(loader/safeLoad (conv/trustedResourceUrlFromString (str %1)) %2)
    (exists? loader/load) #(loader/load (str %1) %2)
    :else (throw (ex-info "No remote script loading function found." {}))))

(defn reload-file-in-html-env
  [request-url callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (doto (gloader (add-cache-buster request-url) #js {:cleanupWhenDone true})
    (.addCallback #(apply callback [true]))
    (.addErrback  #(apply callback [false]))))

(def ^:export write-script-tag-import reload-file-in-html-env)

(defn ^:export worker-import-script [request-url callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (callback (try
              (do (.importScripts js/self (add-cache-buster request-url))
                  true)
              (catch js/Error e
                (glog/error logger (str  "Figwheel: Error loading file " request-url))
                (glog/error logger e)
                false))))

(defn ^:export create-node-script-import-fn []
  (let [node-path-lib (js/require "path")
        ;; just finding a file that is in the cache so we can
        ;; figure out where we are
        util-pattern (str (.-sep node-path-lib)
                          (.join node-path-lib "goog" "bootstrap" "nodejs.js"))
        util-path (gobj/findKey js/require.cache (fn [v k o] (gstring/endsWith k util-pattern)))
        parts     (-> (string/split util-path #"[/\\]") pop pop)
        root-path (string/join (.-sep node-path-lib) parts)]
    (fn [request-url callback]
      (assert (string? request-url) (not (nil? callback)))
      (let [cache-path (.resolve node-path-lib root-path request-url)]
        (gobj/remove (.-cache js/require) cache-path)
        (callback (try
                    (js/require cache-path)
                    (catch js/Error e
                      (glog/error logger (str  "Figwheel: Error loading file " cache-path))
                      (glog/error logger e)
                      false)))))))

(def host-env
  (cond
    (not (nil? goog/nodeGlobalRequire)) :node
    (not (nil? goog/global.document)) :html
    (and (exists? goog/global.navigator)
         (= goog/global.navigator.product "ReactNative"))
    :react-native
    (and
     (nil? goog/global.document)
     (exists? js/self)
     (exists? (.-importScripts js/self)))
    :worker))

(def reload-file*
  (condp = host-env
    :node (create-node-script-import-fn)
    :html write-script-tag-import
    :worker worker-import-script
    (fn [a b] (throw "Reload not defined for this platform"))))

;; TODO Should just leverage the import script here somehow
(defn reload-file [{:keys [request-url] :as file-msg} callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (glog/fine logger (str "Attempting to load " request-url))
  ((or (gobj/get goog.global "FIGWHEEL_IMPORT_SCRIPT") reload-file*)
   request-url
   (fn [success?]
     (if success?
       (do
         (glog/fine logger (str "Successfully loaded " request-url))
         (apply callback [(assoc file-msg :loaded-file true)]))
       (do
         (glog/error logger (str  "Error loading file " request-url))
         (apply callback [file-msg]))))))

;; for goog.require consumption
(defonce reload-promise-chain (atom (Promise. #(%1 true))))

(defn queued-file-reload
  ([url] (queued-file-reload url nil))
  ([url opt-source-text]
   (when-let [next-promise-fn
              (cond opt-source-text
                #(.then %
                        (fn [_]
                          (Promise.
                           (fn [r _]
                             (try (js/eval opt-source-text)
                                  (catch js/Error e
                                    (glog/error logger e)))
                             (r true)))))
                url
                #(.then %
                        (fn [_]
                          (Promise.
                           (fn [r _]
                             (reload-file {:request-url url}
                                          (fn [file-msg]
                                            (r true))))))))]
     (swap! reload-promise-chain next-promise-fn))))

;; TODO fix figwheel-core to use this
(defn ^:export after-reloads [f]
  ;; TODO simplify and verify it works
  (swap! reload-promise-chain #(.then % (fn [_] (Promise. (fn [r _] (f) (r true)))))))

;; --------------------------------------------------------------
;; Websocket REPL
;; --------------------------------------------------------------

(goog-define connect-url "ws://localhost:3449/figwheel-connect")

(def state (atom {}))
(def storage (storage-factory/createHTML5SessionStorage "figwheel.repl"))

;; TODO make this work when session storage doesn't exist
(defn ^:export session-name []
  (.get storage (str ::session-name)))

(defn ^:export session-id []
  (.get storage (str ::session-id)))

(defn response-for [{:keys [uuid websocket]} response-body]
  (cond->
        {:uuid uuid
         :session-name (session-name)
         :response response-body}
    (and (nil? websocket) (session-id))
    (assoc :session-id (session-id))))

(defn respond-to [{:keys [websocket http-url] :as old-msg} response-body]
  (let [response (response-for old-msg response-body)]
    (cond
      websocket
      (.send websocket (pr-str response))
      http-url
      (xhrio/send http-url
                  (fn [e] (glog/info logger (.isSuccess (.-target e))))
                  "POST"
                  (pr-str response)))))

(defmulti message :op)
(defmethod message "naming" [msg]
  (when-let [sn (:session-name msg)]
    (swap! state assoc :session-name sn)
    (.set storage (str ::session-name) sn))
  (when-let [sid (:session-id msg)]
    (swap! state assoc :session-id sid)
    (.set storage (str ::session-id) sid))
  (glog/info logger (str "Session ID: "   (.get storage (str ::session-id))))
  (glog/info logger (str "Session Name: " (.get storage (str ::session-name)))))

(defmethod message "ping" [msg])

(def ^:dynamic *eval-js* js/eval)

(let [ua-product-fn
      #(cond
         (not (nil? goog/nodeGlobalRequire)) :chrome
         product/SAFARI    :safari
         product/CHROME    :chrome
         product/FIREFOX   :firefox
         product/IE        :ie)]
  (defn eval-javascript** [code]
    (let [ua-product (ua-product-fn)]
      (try
        (let [sb (js/goog.string.StringBuffer.)]
          (binding [cljs.core/*print-newline* false
                    cljs.core/*print-fn* (fn [x] (.append sb x))]
            (let [result-value (*eval-js* code)]
              {:status :success
               :out (str sb)
               :ua-product ua-product
               :value result-value})))
        (catch js/Error e
          {:status :exception
           :value (pr-str e)
           :ua-product ua-product
           :stacktrace (.-stack e)})
        (catch :default e
          {:status :exception
           :ua-product ua-product
           :value (pr-str e)
           :stacktrace "No stacktrace available."})))))

(defmethod message "eval" [{:keys [code] :as msg}]
  (let [result (eval-javascript** code)]
    (respond-to msg result)))

(defmethod message "messages" [{:keys [messages http-url]}]
  (doseq [msg messages]
    (message (cond-> (js->clj msg :keywordize-keys true)
               http-url (assoc :http-url http-url)))))

(defn fill-url-template [connect-url']
  (if (= host-env :html)
      (-> connect-url'
          (string/replace "[[client-hostname]]" js/location.hostname)
          (string/replace "[[client-port]]" js/location.port))
      connect-url'))

(defn make-url [connect-url']
  (doto (guri/parse (fill-url-template (or connect-url' connect-url)))
    (.setQueryData (cond-> (.add (QueryData.) "fwsid" (or (session-id) (random-uuid)))
                     (session-name) (.add "fwsname" (session-name))))))

(defn exponential-backoff [attempt]
  (* 1000 (min (js/Math.pow 2 attempt) 20)))

(defn connect [& [websocket-url']]
  ;; TODO take care of forwarding print output to the connection
  (let [websocket (goog.net.WebSocket.)
        url (str (make-url websocket-url'))]
    (patch-goog-base)
    (doto websocket
      (.addEventListener goog.net.WebSocket.EventType.MESSAGE
                         (fn [e]
                           (when-let [msg (gobj/get e "message")]
                             (try
                               (debug msg)
                               (message (assoc
                                         (js->clj (js/JSON.parse msg) :keywordize-keys true)
                                         :websocket websocket))
                               (catch js/Error e
                                 (glog/error logger e))))))
      (.addEventListener goog.net.WebSocket.EventType.OPENED
                         (fn [e]
                           (js/console.log "OPENED")
                           (js/console.log e)))
      (.open url))))

;; -----------------------------------------------------------
;; HTTP simple and long polling
;; -----------------------------------------------------------

(defn http-get [url]
  (Promise. (fn [succ err]
              (xhrio/send url (fn [e]
                                (let [xhr (gobj/get e "target")]
                                  (if (.isSuccess xhr)
                                    (succ (.getResponseJson xhr))
                                    (err xhr))))))))

(declare http-connect http-connect*)

(defn poll [msg-fn connect-url']
  (.then (http-get (make-url connect-url'))
         (fn [msg]
           (msg-fn msg)
           (js/setTimeout #(poll msg-fn connect-url') 500))
         (fn [e] ;; lost connection
           (http-connect connect-url'))))

(defn long-poll [msg-fn connect-url']
  (.then (http-get (make-url connect-url'))
         (fn [msg]
           (msg-fn msg)
           (long-poll msg-fn connect-url'))
         (fn [e] ;; lost connection
           (http-connect connect-url'))))

(defn http-connect* [attempt connect-url']
  (let [url (make-url connect-url')
        surl (str url)
        msg-fn (fn [msg]
                 (try
                   (debug (pr-str msg))
                   (message (assoc (js->clj msg :keywordize-keys true)
                                   :http-url surl))
                   (catch js/Error e
                     (glog/error logger e))))]
    (doto (.getQueryData url)
      (.add "fwinit" "true"))
    (.then (http-get url)
           (fn [msg]
             (let [typ (gobj/get msg "connection-type")]
               (glog/info logger (str "Connected: " typ))
               (msg-fn msg)
               (if (= typ "http-long-polling")
                 (long-poll msg-fn connect-url')
                 (poll msg-fn connect-url'))))
           (fn [e];; didn't connect
             (when (instance? js/Error e)
               (glog/error logger e))
             (when (and (instance? goog.net.XhrIo e) (.getResponseBody e))
               (debug (.getResponseBody e)))
             (let [wait-time (exponential-backoff attempt)]
               (glog/info logger (str "HTTP Connection Error: next connection attempt in " (/ wait-time 1000) " seconds"))
               (js/setTimeout #(http-connect* (inc attempt) connect-url')
                              wait-time))))))

(defn http-connect [& [connect-url']]
  (http-connect* 0 connect-url'))

))


#?(:clj (do

(defonce ^:private listener-set (atom #{}))
(defn add-listener [f] (swap! listener-set conj f) nil)
(defn remove-listener [f] (swap! listener-set disj f) nil)

(declare name-list)

(defn log [& args]
  (spit "server.log" (apply prn-str args) :append true))

(defonce scratch (atom {}))

(def ^:dynamic *server* nil)

(defn parse-query-string [qs]
  (when (string? qs)
    (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" qs)]
               [(keyword k) v]))))

;; ------------------------------------------------------------------
;; Connection management
;; ------------------------------------------------------------------

(defonce ^:dynamic *connections* (atom {}))

(defn taken-names [connections]
  (set (mapv :session-name (vals connections))))

(defn available-names [connections]
  (set/difference name-list (taken-names connections)))

(defn negotiate-id [ring-request connections]
  (let [query (parse-query-string (:query-string ring-request))
        sid (:fwsid query (str (java.util.UUID/randomUUID)))
        sname (or (some-> connections (get sid) :session-name)
                  (when-let [chosen-name (:fwsname query)]
                    (when-not ((taken-names connections) chosen-name)
                      chosen-name))
                  (rand-nth (seq (available-names connections))))]
    [sid sname]))

;; TODO if connection exists ensure connection is open
;; or perhaps filter all connections to list of open connections
;; here
(defn create-connection! [ring-request options]
  (let [[sess-id sess-name] (negotiate-id ring-request @*connections*)
        conn (merge (select-keys ring-request [:server-port :scheme :uri :server-name :query-string :request-method])
                    {:session-name sess-name
                     :session-id sess-id
                     :created-at (System/currentTimeMillis)}
                    options)]
    (swap! *connections* assoc sess-id conn)
    conn))

(defn remove-connection! [{:keys [session-id] :as conn}]
  (swap! *connections* dissoc session-id))

(defn receive-message! [data]
  (when-let [data
             (try (edn/read-string data)
                  (catch Throwable t (binding [*out* *err*] (clojure.pprint/pprint (Throwable->map t)))))]
    (doseq [f @listener-set]
      (try (f data) (catch Throwable ex)))))

(defn naming-response [{:keys [session-name session-id type] :as conn}]
  (json/write-str {:op :naming
                   :session-name session-name
                   :session-id session-id
                   :connection-type type}))

;; ------------------------------------------------------------------
;; Websocket behavior
;; ------------------------------------------------------------------

(defn abstract-websocket-connection [connections]
  (let [conn (volatile! nil)]
    {:on-connect (fn [{:keys [request send-fn close-fn is-open-fn]
                       :as connect-data}]
                   ;; TODO remove dev only
                   (swap! scratch assoc :ring-request request)
                   (binding [*connections* connections]
                     (let [conn' (create-connection!
                                  request
                                  {:type :websocket
                                   :is-open-fn is-open-fn
                                   :close-fn close-fn
                                   :send-fn (fn [_ data]
                                              (send-fn data))})]
                       (vreset! conn conn')
                       (send-fn (naming-response conn')))))
     :on-close   (fn [status] (binding [*connections* connections]
                                (remove-connection! @conn)))
     :on-receive (fn [data] (binding [*connections* connections]
                              (receive-message! data)))}))

;; ------------------------------------------------------------------
;; http polling
;; ------------------------------------------------------------------

(defn json-response [json-body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body json-body})

;; TODO reconnect should return naming response
;; TODO perhaps all responses should return connection type
;; TODO change messages to an atom and use similar capture method that long polling uses

;; TODO figure out is-open-function for http long and short polling

(defn http-polling-send [conn data]
  (swap! (::comm-atom conn) update :messages (fnil conj []) data))

(defn http-polling-connect [ring-request]
  (let [{:keys [fwsid fwinit]} (-> ring-request :query-string parse-query-string)]
    ;; new connection create the connection
    (cond
      (not (get @*connections* fwsid))
      (let [conn (create-connection! ring-request
                                     {:type :http-polling
                                      ::comm-atom (atom {})
                                      :send-fn http-polling-send})]
        (json-response (naming-response conn)))
      fwinit
      (let [conn (get @*connections* fwsid)]
        (swap! *connections* assoc-in [fwsid :created-at] (System/currentTimeMillis))
        (json-response (naming-response conn)))
      :else
      ;; otherwise we are polling
      (let [messages (volatile! [])
            comm-atom (get-in @*connections* [fwsid ::comm-atom])]
        (swap! *connections* assoc-in [fwsid ::alive-at] (System/currentTimeMillis))
        (swap! comm-atom update :messages (fn [msgs] (vreset! messages (or msgs [])) []))
        (json-response
         (json/write-str {:op :messages
                          :messages (mapv json/read-json @messages)
                          :connection-type :http-polling}))))))

(defn http-polling-endpoint [ring-request]
  (condp = (:request-method ring-request)
    :get (http-polling-connect ring-request)
    :post (do (receive-message! (slurp (:body ring-request)))
              {:status 200
               :headers {"Content-Type" "text/html"}
               :body "Received"})))

;; simple http polling can be included in any ring middleware stack
(defn http-polling-middleware [handler path connections]
  (fn [ring-request]
    (if-not (.startsWith (:uri ring-request) path)
      (handler ring-request)
      (binding [*connections* connections]
        (http-polling-endpoint ring-request)))))

;; ------------------------------------------------------------------
;; http async polling - long polling
;; ------------------------------------------------------------------

;; TODO explore shorter long polling timeouts
;; TODO make long polling client reconnect when server isn't present
;; TODO perhaps different endpoint for long polling?

;; TODO probably better to base the simple polling on a similar pattern

;; agents would be easier but heavier and agent clean up is harder
(defn long-poll-send [comm-atom msg]
  (let [data (volatile! nil)
        add-message #(if msg
                       (update % :messages (fnil conj []) msg)
                       %)]
    (swap! comm-atom
           (fn [{:keys [respond messages] :as comm}]
             (if (and respond (or (not-empty messages) msg))
               ;; only reset in a send state
               (do (vreset! data (add-message comm)) {})
               (add-message comm))))
    (when-let [{:keys [respond messages]} @data]
      (respond {:status 200
                :headers {"Content-Type" "application/json"}
                ;; TODO fix this
                :body (json/write-str {:op :messages
                                       :messages (mapv json/read-json messages)})}))))

(defn long-poll-capture-respond [comm-atom respond]
  (let [has-messages (volatile! false)]
    (swap! comm-atom (fn [{:keys [messages] :as comm}]
                       (vreset! has-messages (not (empty? messages)))
                       (assoc comm :respond respond)))
    (when has-messages
      (long-poll-send comm-atom nil))))

(defn http-long-polling-connect [ring-request respond raise]
  (let [{:keys [fwsid fwinit]} (-> ring-request :query-string parse-query-string)]
    ;; new connection create the connection
    (if (not (get @*connections* fwsid))
      (let [conn (create-connection!
                  ring-request
                  {:type :http-long-polling
                   ::comm-atom (atom {:messages []})
                   :send-fn (fn [conn msg]
                              (long-poll-send (::comm-atom conn) msg))})]
        (respond {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (naming-response conn)}))
      (let [conn (get @*connections* fwsid)]
        (if fwinit
          (do
            ;; TODO place this after the response?
            (swap! *connections* assoc-in [fwsid :created-at] (System/currentTimeMillis))
            (respond {:status 200
                      :headers {"Content-Type" "application/json"}
                      :body (naming-response conn)}))
          (do
            (long-poll-capture-respond (::comm-atom conn) respond)
            (swap! *connections* assoc-in [fwsid ::alive-at] (System/currentTimeMillis))))))))

(defn http-long-polling-endpoint [ring-request send raise]
  (condp = (:request-method ring-request)
    :get (http-long-polling-connect ring-request send raise)
    :post (do (receive-message! (slurp (:body ring-request)))
              (send {:status 200
                     :headers {"Content-Type" "text/html"}
                     :body "Received"}))))

(defn asyc-http-polling-middleware [handler path connections]
  (fn [ring-request send raise]
    (swap! scratch assoc :async-request ring-request)
    (if-not (.startsWith (:uri ring-request) path)
      (handler ring-request send raise)
      (binding [*connections* connections]
        (try
          (http-long-polling-endpoint ring-request send raise)
          (catch Throwable e
            (raise e)))))))

;; ---------------------------------------------------
;; ReplEnv implmentation
;; ---------------------------------------------------

(defn connections-available [repl-env]
  (sort-by
   :created-at >
   (filter (or (some-> repl-env :connection-filter deref)
               identity)
           (vals @*connections*))))

(defn wait-for-connection [repl-env]
  (loop []
    (when (empty? (connections-available repl-env))
      (Thread/sleep 500)
      (recur))))

;; may turn this into a multi method
(defn connection-send [{:keys [send-fn] :as conn} data]
  (send-fn conn data))

(defn send-for-eval [connections js]
  (let [prom (promise)]
    (doseq [conn connections]
      (let [uuid (str (java.util.UUID/randomUUID))
            listener (fn listen [msg]
                       (when (= uuid (:uuid msg))
                         (when-let [result (:response msg)]
                           (deliver prom (vary-meta
                                          result
                                          assoc ::message msg)))
                         (remove-listener listen)))]
        (add-listener listener)
        (connection-send
         conn
         (json/write-str
          (assoc (select-keys conn [:session-id :session-name])
                 :uuid uuid
                 :op :eval
                 :code js)))))
    prom))

(let [timeout-val (Object.)]
  (defn evaluate [{:keys [focus-session-name ;; just here for consideration
                          repl-timeout
                          broadcast] :as repl-env} js]
    (wait-for-connection repl-env)
    ;; get the correct connection
    (let [connections (connections-available repl-env)
          ;; session focus
          connections (if-let [focus-conn
                               (and @focus-session-name
                                    (first (filter (fn [{:keys [session-name]}]
                                                     (= @focus-session-name
                                                        session-name))
                                                   connections)))]
                        [focus-conn]
                        (do
                          (reset! focus-session-name nil)
                          connections))
          youngest-connection (first connections)
          result (let [v (deref (send-for-eval (if broadcast
                                                 connections
                                                 [youngest-connection])
                                               js)
                                (or repl-timeout 8000)
                                timeout-val)]
                   (if (= timeout-val v)
                     {:status :exception
                      :value "Eval timed out!"
                      :stacktrace "No stacktrace available."}
                     v))]
      (when-let [out (:out result)]
        (when (not (string/blank? out))
          (println (string/trim-newline out))))
      result)))

(defn run-default-server [options connections]
  (require 'figwheel.server)
  (let [fw-server-run (resolve 'figwheel.server/run-server)
        ring-stack    (resolve 'figwheel.server/ring-stack)
        wrap-cors     (resolve 'figwheel.server/wrap-async-cors)]
    (fw-server-run (ring-stack #(http-polling-middleware % "/figwheel-connect" connections))
                   (assoc options
                          #_:async-handlers
                          #_{"/figwheel-connect"
                           (-> (fn [ring-request send raise]
                                 (send  {:status 200
                                         :headers {"Content-Type" "text/html"}
                                         :body "Received Yep"}))
                               (asyc-http-polling-middleware "/figwheel-connect" connections)
                               (wrap-cors
                                :access-control-allow-origin #".*"
                                :access-control-allow-methods [:head :options :get :put :post :delete :patch]))}
                          ::abstract-websocket-connection
                          (abstract-websocket-connection connections)))))

(defrecord FigwheelReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (when (and
           (or (not (bound? #'*server*))
               (nil? *server*))
           (nil? @(:server-kill this)))
      (let [server (run-default-server
                    ;; TODO merge in options here for ring-handler
                    {:port 9500 :join? false}
                    *connections*)]
        (reset! (:server-kill this) (fn [] (.stop server)))))
    (doseq [url (:open-urls this)]
      (try (browse/browse-url url)
           (catch Throwable e
             (->> (str (when-let [m (.getMessage e)] (str ": " m)))
                  (format "Failed to open url %s %s" url)
                  println))))
    #_(wait-for-connection this))
  (-evaluate [this _ _  js]
    (evaluate this js))
  (-load [this provides url]
    ;; load a file into all the appropriate envs
    (when-let [js-content (try (slurp url) (catch Throwable t))]
      (evaluate this js-content)))
  (-tear-down [{:keys [server-kill]}]
    (when-let [kill-fn @server-kill]
      (reset! server-kill nil)
      (kill-fn)))
  cljs.repl/IReplEnvOptions
  (-repl-options [this])
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (cljs.stacktrace/parse-stacktrace this st err opts)))

(defn repl-env* [{:keys [host port worker-name-prefix connection-filter ring-handler]
                  :or {connection-filter identity
                       port 9500
                       host "localhost"} :as opts}]
  (merge (FigwheelReplEnv.)
         {:server-kill (atom nil)
          :open-urls nil
          :connection-filter (atom connection-filter)
          :focus-session-name (atom nil)
          :broadcast false
          :port port
          :host host}
         opts))

;; ------------------------------------------------------
;; Connection management
;; ------------------------------------------------------
;;  mostly for use from the REPL

(defn list-connections []
  (let [conns (connections-available cljs.repl/*repl-env*)
        longest-name (apply max (cons (count "Session Name")
                                      (map (comp count :session-name) conns)))]
    (println (format (str "%-" longest-name "s %7s %s")
                     "Session Name"
                     "Age"
                     "URL"))
    (doseq [{:keys [session-name uri query-string created-at]} conns]
      (println (format (str "%-" longest-name "s %6sm %s")
                       session-name
                       (Math/round (/ (- (System/currentTimeMillis) created-at) 60000.0))
                       uri)))))

(defn will-eval-on []
  (if-let [n @(:focus-session-name cljs.repl/*repl-env*)]
    (println "Focused On: " n)
    (println "Will Eval On: " (->> (connections-available cljs.repl/*repl-env*)
                                  first
                                  :session-name))))

(defn conns* []
  (will-eval-on)
  (list-connections))

(defmacro conns []
  (conns*))

(defn focus* [session-name]
  (let [names (map :session-name (connections-available cljs.repl/*repl-env*))
        session-name (name session-name)]
    (if ((set names) session-name)
      (str "Focused On: " (reset! (:focus-session-name cljs.repl/*repl-env*) session-name))
      (str "Error: " session-name " not in " (pr-str names)))))

(defmacro focus [session-name]
  (focus* session-name))

;; TODOS
;; - learn more about https
;; - make work on node and other platforms
;; - make http polling connection as backup
;; - make http polling connection a ring handler

(comment
  (def serve (run-server not-found {:port 9500 :join? false}))
  (.stop serve)

  (def re (repl-env* {}))
  (cljs.repl/-setup re {})
  (cljs.repl/-tear-down re)

  (connections-available re)

  (evaluate re "33")

  (mapv #(do #_(Thread/sleep 1)
             (evaluate re (str %)))
        (range 10))

  (negotiate-id (:ring-request @scratch) @*connections*)

  (def channel (:body (:async-request @scratch)))

  (.isReady channel)

  scratch
  *connections*
  (parse-query-string (:query-string (:ring-request @scratch)))
  (negotiate-name (:ring-request @scratch) @*connections*)
  (reset! *connections* (atom {}))

  (binding [cljs.repl/*repl-env* re]
    (conns*)
    #_(focus* 'Judson))

  )

(def name-list
  (set (map str '[Sal Julietta Dodie Janina Krista Freeman Angila Cathy Brant Porter Marty Jerrell Stephan Glenn Palmer Carmelina Monroe Eufemia Ciara Thu Stevie Dee Shamika Jazmin Doyle Roselle Lucien Laveta Marshall Rosy Hilde Yoshiko Nicola Elmo Tana Odelia Gigi Mac Tanner Johnson Roselia Gilberto Marcos Shelia Kittie Bruno Leeanne Elicia Miyoko Lilliana Tatiana Steven Vashti Rolando Korey Selene Emilio Fred Marvin Eduardo Jolie Lorine Epifania Jeramy Eloy Melodee Lilian Kim Cory Daniel Grayce Darin Russ Vanita Yan Quyen Kenda Iris Mable Hong Francisco Abdul Judson Boyce Bridget Cecil Dirk Janetta Kelle Shawn Rema Rosie Nakesha Dominick Jerald Shawnda Enrique Jose Vince])))

#_(defonce ^:private message-loop
  (doto (Thread.
         #(let [x (.take messageq)
                listeners @listener-set]
            (doseq [f listeners]
              (try
                (f x)
                (catch Throwable ex)))
            (recur))
         (str ::message-loop))
    (.setDaemon true)
    (.start)))




          ))
