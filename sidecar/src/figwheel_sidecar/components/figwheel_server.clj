(ns figwheel-sidecar.components.figwheel-server
  (:require
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :as utils]

   [clojure.java.io :as io]
   [clojure.edn :as edn]
   
   [clojure.core.async :refer [go-loop <!! <! timeout]]

   [compojure.route :as route]
   [compojure.core :refer [routes GET]]
   [ring.util.response :refer [resource-response]]
   [ring.middleware.cors :as cors]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   
   [com.stuartsierra.component :as component]))

(defprotocol ChannelServer
  (-send-message [this channel-id msg-data callback])
  (-connection-data [this]))


(defn get-open-file-command [{:keys [open-file-command]} {:keys [file-name file-line]}]
  (when open-file-command
    (if (= open-file-command "emacsclient")
      ["emacsclient" "-n" (str "+" file-line) file-name] ;; we are emacs aware
      [open-file-command file-name file-line])))

(defn read-msg [data]
  (try
    (let [msg (edn/read-string data)]
      (if (and (map? msg) (:figwheel-event msg)) msg {}))
    (catch Exception e
      (println "Figwheel: message from client couldn't be read!")
      {})))

;; should make this extendable with multi-method
(defn handle-client-msg [{:keys [browser-callbacks] :as server-state} data]
  (when data
    (let [msg (read-msg data)]
      (if (= "callback" (:figwheel-event msg))
        (when-let [cb (get @browser-callbacks (:callback-name msg))]
          (cb (:content msg)))
        (when-let [command (and (= "file-selected" (:figwheel-event msg))
                                (get-open-file-command server-state msg))]
          (try
            (.exec (Runtime/getRuntime) (into-array String command))
            (catch Exception e
              (println "Figwheel: there was a problem running the open file command - " command))))))))

(defn add-build-id [{:keys [build-id]} msg]
  (if build-id
    (assoc msg :build-id build-id)
    msg))

(defn message* [opts msg-name data]
  (merge data
         (add-build-id opts
                       { :msg-name msg-name 
                         :project-id (:unique-id opts)})))

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
                (fn [data] (handle-client-msg server-state data)))

    ;; Keep alive!!
    ;; 
    (go-loop []
      (<! (timeout 5000))
      (when (open? wschannel)
        (send! wschannel (prn-str (message* server-state :ping {})))
        (recur)))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state (:params request) channel))))

(defn server
  "This is the server. It is complected and its OK. Its trying to be a basic devel server and
   also provides the figwheel websocket connection."
  [{:keys [server-port server-ip http-server-root resolved-ring-handler] :as server-state}]
  (try
    (-> (routes
         (GET "/figwheel-ws/:desired-build-id" {params :params} (reload-handler server-state))
         (GET "/figwheel-ws" {params :params} (reload-handler server-state))       
         (route/resources "/" {:root http-server-root})
         (or resolved-ring-handler (fn [r] false))
         (GET "/" [] (resource-response "index.html" {:root http-server-root}))
         (route/not-found "<h1>Page not found</h1>"))
        ;; adding cors to support @font-face which has a strange cors error
        ;; super promiscuous please don't uses figwheel as a production server :)
        (cors/wrap-cors
         :access-control-allow-origin #".*"
         :access-control-allow-methods [:head :options :get :put :post :delete :patch])
        (run-server (let [config {:port server-port :worker-name-prefix "figwh-httpkit-"}]
                      (if server-ip
                        (assoc config :ip server-ip)
                        config))))
    (catch java.net.BindException e
      (println "Port" server-port "is already being used. Are you running another Figwheel instance? If you want to run two Figwheel instances add a new :server-port (i.e. :server-port 3450) to Figwheel's config options in your project.clj")
      (System/exit 0))))

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

(defn send-message! [{:keys [file-change-atom] :as st} msg-name data]
  (swap! file-change-atom append-msg
         (message* st msg-name
                   (setup-callback st data))))

;; remove resource paths here 
(defn create-initial-state [{:keys [unique-id
                                    http-server-root
                                    server-port
                                    server-ip
                                    ring-handler
                                    resolved-ring-handler
                                    open-file-command
                                    compile-wait-time

                                    ] :as opts}]
      (merge
       opts ;; allow other options to flow through
       {
        ;; seems like this id should be different for every
        ;; server restart thus forcing the client to reload
        :unique-id (or unique-id (.getCanonicalPath (io/file "."))) 
        :http-server-root (or http-server-root "public")
        :server-port (or server-port 3449)
        :server-ip server-ip
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
     (println (str "Figwheel: Starting server at http://localhost:" (:server-port state)))
     (assoc state :http-server (server state)))))

(defn stop-server [{:keys [http-server]}]
  (http-server))

(defn prep-message [{:keys [unique-id] :as this} channel-id msg-data callback]
  (-> msg-data
        (assoc
               :project-id (:unique-id unique-id)
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
  (-connection-data [{:keys [connection-count]}] @connection-count))

(defn send-message [figwheel-server channel-id msg-data]
  (-send-message figwheel-server channel-id msg-data nil))

(defn send-message-with-callback [figwheel-server channel-id msg-data callback]
  (-send-message figwheel-server channel-id msg-data callback))

(defn connection-data [figwheel-server]
  (-connection-data figwheel-server))




(defn ensure-array-map [all-builds]
  (into (array-map)
        (map (juxt :id identity)
             (if (map? all-builds) (vals all-builds) all-builds))))

(defn log-writer [figwheel-options]
  (let [logfile-path (or (:server-logfile figwheel-options) "figwheel_server.log")]
    (if (false? (:repl figwheel-options))
      *out*
      (io/writer logfile-path :append true))))

(defn cljs-build-fn [{:keys [cljs-build-fn]}]
  (or (utils/require-resolve-handler cljs-build-fn)
      ;; should probably make a separate file of build function examples
      (when-let [default-handler (resolve 'figwheel-sidecar.components.cljs-autobuild/figwheel-build)]
        @default-handler)))

(defn figwheel-server [figwheel-options all-builds]
  (let [prepped-fig-options (config/prep-options figwheel-options)
        all-builds          (map utils/add-compiler-env (config/prep-builds all-builds))
        all-builds (ensure-array-map all-builds)
        
        initial-state       (create-initial-state prepped-fig-options)
        figwheel-opts (assoc initial-state
                             :builds all-builds       
                             :log-writer    (log-writer prepped-fig-options)
                             :cljs-build-fn (cljs-build-fn prepped-fig-options))]
    (map->FigwheelServer figwheel-opts)))
