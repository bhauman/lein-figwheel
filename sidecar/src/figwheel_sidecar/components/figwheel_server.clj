(ns figwheel-sidecar.components.figwheel-server
  (:require
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :as utils :refer [bind-logging]]
   [figwheel-sidecar.build-utils :as butils]

   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.edn :as edn]
   [clojure.stacktrace :as stack]
   [clojure.core.async :refer [go-loop <!! <! timeout]]

   [ring.util.response :refer [resource-response] :as response]
   [ring.util.mime-type :as mime]
   [ring.middleware.cors :as cors]
   [ring.middleware.not-modified :as not-modified]
   [co.deps.ring-etag-middleware :as etag]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]

   [com.stuartsierra.component :as component]))

(defprotocol ChannelServer
  (-send-message [this channel-id msg-data callback])
  (-connection-data [this])
  (-actual [this]))

(defn get-open-file-command [open-file-command {:keys [file-name file-line file-column]}]
  (when open-file-command
    (if (= open-file-command "emacsclient")
      (cond-> ["emacsclient" "-n"]
        (not (nil? file-line))
        (conj  (str "+" file-line
                    (when (not (nil? file-column))
                      (str ":" file-column))))
        true (conj file-name))
      ;; must pass arguments to clojure.java.shell/sh as strings, not numeric values
      [open-file-command file-name (str file-line) (str file-column)])))

(defn read-msg [data]
  (try
    (let [msg (edn/read-string data)]
      (if (and (map? msg) (:figwheel-event msg)) msg {}))
    (catch Exception e
      (println "Figwheel: message from client couldn't be read!")
      {})))

(defn validate-file-selected-msg [{:keys [file-name file-line file-column] :as msg}]
  (and file-name (.exists (io/file file-name))
       (cond-> msg
         file-line   (assoc :file-line (java.lang.Integer/parseInt file-line))
         file-column (assoc :file-column (java.lang.Integer/parseInt file-column)))))

(defn exec-open-file-command [{:keys [open-file-command] :as server-state} msg]
  (when-let [msg (#'validate-file-selected-msg msg)]
    (if-let [command (get-open-file-command open-file-command msg)]
      (try
        (let [result (apply shell/sh command)]
          (if (zero? (:exit result))
            (println "Successful open file command: " (pr-str command))
            (println "Failed to call open file command: " (pr-str command)))
          (when-not (string/blank? (:out result))
            (println "OUT:")
            (println (:out result)))
          (when-not (string/blank? (:err result))
            (println "ERR:")
            (println (:err result)))
          (flush))
        (catch Exception e
          (println "Figwheel: there was a problem running the open file command - "
                   command)
          (println (.getMessage e))))
      (println "Figwheel: Can't open " (pr-str (vals (select-keys msg [:file-name :file-line :file-column])))
               "No :open-file-command supplied in the config."))))

;; should make this extendable with multi-method
(defn handle-client-msg [{:keys [browser-callbacks log-writer] :as server-state} data]
  (when data
    (let [msg (read-msg data)]
      (bind-logging log-writer
        (if (= "callback" (:figwheel-event msg))
          (when-let [cb (get @browser-callbacks (:callback-name msg))]
            (cb (:content msg)))
          (when (= "file-selected" (:figwheel-event msg))
            (exec-open-file-command server-state msg)))))))

(defn update-connection-count [connection-count build-id f]
  (swap! connection-count update-in [build-id] (fnil f 0)))

;; this sets up the websocket connection
;; TODO look more at this
(defn setup-file-change-sender [{:keys [file-change-atom compile-wait-time connection-count] :as server-state}
                                {:keys [desired-build-id] :as params}
                                wschannel]
  (let [watch-key (keyword (gensym "message-watch-"))]
    (update-connection-count connection-count desired-build-id inc)
    (add-watch
     file-change-atom
     watch-key
     (fn [_ _ o n]
       (let [msg (first n)]
         (when (and msg
                    (or
                     ;; broadcast all css messages
                     (= ::broadcast (:build-id msg))
                     ;; if its nil you get it all
                     (nil? desired-build-id)
                     ;; otherwise you only get messages for your build id
                     (= desired-build-id (:build-id msg))))
           (<!! (timeout compile-wait-time))
           (when (open? wschannel)
             (send! wschannel (prn-str msg)))))))

    (on-close wschannel
              (fn [status]
                (update-connection-count connection-count desired-build-id dec)
                (remove-watch file-change-atom watch-key)
                #_(println "Figwheel: client disconnected " status)))

    (on-receive wschannel
                (fn [data] (#'handle-client-msg server-state data
                            )))

    ;; Keep alive!!
    ;;
    (go-loop []
      (<! (timeout 5000))
      (when (open? wschannel)
        (send! wschannel (prn-str  {:msg-name :ping
                                    :project-id (:unique-id server-state)}))
        (recur)))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state (:params request) channel))))

(defn log-output-to-figwheel-server-log [handler log-writer]
  (fn [request]
    (bind-logging
     log-writer
     (try
       (handler request)
       (catch Throwable e
         (let [message (.getMessage e)
               trace (with-out-str (stack/print-cause-trace e))]
           (println message)
           (println trace)
           {:status 400
            :headers {"Content-Type" "text/html"}
            :body (str "<h1>" message "</h1>"
                       "<pre>"
                       trace
                       "</pre>")}))))))

;; File caching strategy:
;;
;; ClojureScript (as of March 2018) copies the last-modified date of Clojure
;; source files to the compiled JavaScript target files. Closure compiled
;; JavaScript (goog.base), it gets the time that it was compiled (i.e. now).
;;
;; Neither of these dates are particularly useful to use for caching. Closure
;; compiled JavaScript doesn't change from run to run, so caching based on
;; last modified date will not achieve as high a hit-rate as possible.
;; ClojureScript files can consume macros that change from run to run, but
;; will still get the same file modification date, so we would run the risk
;; of using stale cached files.
;;
;; Instead, we provide a checksum based ETag. This is based solely on the file
;; content, and so sidesteps both of the issues above. We remove the
;; Last-Modified header from the response to avoid it busting the browser cache
;; unnecessarily.

(defn handle-index [handler root]
  (fn [request]
    (if (= [:get "/"] ((juxt :request-method :uri) request))
      (if-let [resp (some-> (resource-response "index.html" {:root root
                                                             :allow-symlinks? true})
                            (utils/dissoc-in [:headers "Last-Modified"])
                            (response/content-type "text/html; charset=utf-8"))]
        resp
        (handler request))
      (handler request))))

(defn handle-static-resources [handler root]
  (let [add-mime-type (fn [response path]
                        (if-let [mime-type (mime/ext-mime-type path)]
                          (response/content-type response mime-type)
                          response))]
    (fn [{:keys [request-method uri] :as request}]
      (if (= :get request-method)
        (if-let [resp (some-> (resource-response uri {:root root
                                                      :allow-symlinks? true})
                              (utils/dissoc-in [:headers "Last-Modified"])
                              (add-mime-type uri))]
          resp
          (handler request))
        (handler request)))))

(defn parse-build-id [uri]
  (let [[fig-ws build-id :as parts] (rest (string/split uri #"/"))]
    (and (= (count parts) 2)
         (= fig-ws "figwheel-ws")
         (not (string/blank? build-id))
         build-id)))

(defn handle-figwheel-websocket [handler server-state]
  (let [websocket-handler (reload-handler server-state)]
    (fn [{:keys [request-method uri] :as request}]
      (if (= :get request-method)
        (cond
          (= uri "/figwheel-ws")
          (websocket-handler request)

          (parse-build-id uri)
          (websocket-handler
           (assoc-in request [:params :desired-build-id] (parse-build-id uri)))

          :else (handler request))
        (handler request)))))

(defn possible-endpoint [handler possible-fn]
  (if possible-fn
    #(possible-fn %)
    #(handler %)))

(defn wrap-no-cache
  "Add 'Cache-Control: no-cache' to responses.
   This allows the client to cache the response, but
   requires it to check with the server every time to make
   sure that the response is still valid, before using
   the locally cached file.

   This avoids stale files being served because of overzealous
   browser caching, while still speeding up load times by caching
   files."
  [handler]
  (fn [req]
    (some-> (handler req)
      (update :headers assoc
              "Cache-Control" "no-cache"))))

(defn server
  "This is the server. It is complected and its OK. Its trying to be a basic devel server and
   also provides the figwheel websocket connection."
  [{:keys [server-port server-ip http-server-root resolved-ring-handler log-writer] :as server-state}]
  (try
    (->
     (fn [_]
       (response/not-found
        "<div><h1>Figwheel Server: Resource not found</h1><h3><em>Keep on figwheelin'</em></h3></div>"))

     ;; users handler goes last
     (possible-endpoint resolved-ring-handler)

     (handle-static-resources http-server-root)
     (handle-index            http-server-root)
     (handle-figwheel-websocket server-state)

     (wrap-no-cache)
     (etag/wrap-file-etag)
     (not-modified/wrap-not-modified)
     ;; adding cors to support @font-face which has a strange cors error
     ;; super promiscuous please don't uses figwheel as a production server :)
     (cors/wrap-cors
      :access-control-allow-origin #".*"
      :access-control-allow-methods [:head :options :get :put :post :delete :patch])
     (log-output-to-figwheel-server-log log-writer)
     (run-server (let [config {:port server-port :worker-name-prefix "figwh-httpkit-"}]
                   (if server-ip
                     (assoc config :ip server-ip)
                     config))))
    (catch java.net.BindException e
      (throw (ex-info (str "Port " server-port " is already being used. \n"
                           "Are you running another Figwheel instance? \n"
                           "If you want to run two Figwheel instances add a "
                           "new :server-port (i.e. :server-port 3450)\n"
                           "to Figwheel's config options in your project.clj")
                      {:escape-system-exceptions true
                       :reason :unable-to-bind-port})))))

(defn append-msg [q msg] (conj (take 30 q) msg))

(defn setup-callback [{:keys [browser-callbacks]} {:keys [callback] :as msg-data}]
  (if callback
    (let [callback-name (str (gensym "figwheel_callback_"))]
      (swap! browser-callbacks assoc callback-name
             (fn [result]
               (swap! browser-callbacks dissoc callback-name)
               (callback result)))
      (-> msg-data
        (dissoc :callback)
        (assoc :callback-name callback-name)))
    msg-data))

;; remove resource paths here
(defn create-initial-state [{:keys [unique-id
                                    http-server-root
                                    server-port
                                    server-ip
                                    ring-handler
                                    resolved-ring-handler
                                    open-file-command
                                    compile-wait-time
                                    ansi-color-output
                                    ] :as opts}]
      (merge
       opts ;; allow other options to flow through
       {
        ;; seems like this id should be different for every
        ;; server restart thus forcing the client to reload
        :unique-id (or unique-id (.getCanonicalPath (io/file ".")))
        :http-server-root (or http-server-root "public")
        :server-port (or server-port 3449)
        :server-ip (or server-ip "0.0.0.0")
        :ring-handler ring-handler
        ;; TODO handle this better
        :resolved-ring-handler (or resolved-ring-handler
                                   (utils/require-resolve-handler ring-handler))

        :open-file-command open-file-command
        :compile-wait-time (or compile-wait-time 10)

        :file-md5-atom (atom {})

        :file-change-atom (atom (list))
        :browser-callbacks (atom {})
        :connection-count (atom {})
        }))

(defn start-server
  ([] (start-server {}))
  ([opts]
   (let [state (if-not (:file-md5-atom opts)
                 (create-initial-state opts)
                 opts)]
     (println (str "Figwheel: Starting server at http://" (:server-ip state) ":" (:server-port state)))
     (assoc state :http-server (server state)))))

(defn stop-server [{:keys [http-server]}]
  (http-server))

(defn prep-message [{:keys [unique-id] :as this} channel-id msg-data callback]
  (-> msg-data
      (assoc
       :project-id (:unique-id unique-id)
       :figwheel-version config/_figwheel-version_
       :build-id channel-id
       :callback callback)
      (->>
       (filter (comp not nil? second))
       (into {})
       (setup-callback this))))

;; external api

(defrecord FigwheelServer []
  component/Lifecycle
  (start [this]
    (if-not (:http-server this)
      (do
        (map->FigwheelServer (start-server this)))
      this))
  (stop [this]
    (when (:http-server this)
      (println "Figwheel: Stopping Websocket Server")
      (stop-server this))
    (dissoc this :http-server))
  ChannelServer
  (-send-message [{:keys [file-change-atom] :as this} channel-id msg-data callback]
    (->> (prep-message this channel-id msg-data callback)
         (swap! file-change-atom append-msg)))
  (-connection-data [{:keys [connection-count]}] @connection-count)
  (-actual [this] this))

(defn send-message [figwheel-server channel-id msg-data]
  (-send-message figwheel-server channel-id msg-data nil))

(defn send-message-with-callback [figwheel-server channel-id msg-data callback]
  (-send-message figwheel-server channel-id msg-data callback))

(defn connection-data [figwheel-server]
  (-connection-data figwheel-server))

(defn config-options [figwheel-server]
  (-actual figwheel-server))

;; setup server for overall system

(defn ensure-array-map [all-builds]
  (into (array-map)
        (map (juxt :id identity)
             (if (map? all-builds) (vals all-builds) all-builds))))

(defn extract-log-writer [{:keys [repl server-logfile] :as figwheel-options}]
  (if (or (false? repl)
          (false? server-logfile))
    false
    (let [logfile-path (:server-logfile figwheel-options "figwheel_server.log")]
      (io/make-parents logfile-path)
      (io/writer logfile-path :append true))))

(defn extract-cljs-build-fn [{:keys [cljs-build-fn]}]
  (or (utils/require-resolve-handler cljs-build-fn)
      ;; should probably make a separate file of build function examples
      (when-let [default-handler (resolve 'figwheel-sidecar.components.cljs-autobuild/figwheel-build)]
        @default-handler)))

(defn figwheel-server [config-data]
  (let [{:keys [figwheel-options all-builds] :as options}
        (if (config/figwheel-internal-config-data? config-data)
          (:data config-data)
          config-data)
        all-builds (map butils/add-compiler-env (config/prep-builds* all-builds))
        all-builds (ensure-array-map all-builds)

        initial-state       (create-initial-state figwheel-options)
        figwheel-opts (assoc initial-state
                             :builds all-builds
                             :log-writer    (extract-log-writer figwheel-options)
                             :cljs-build-fn (extract-cljs-build-fn figwheel-options))]
    (map->FigwheelServer figwheel-opts)))
