(ns figwheel.repl
  (:require
   [clojure.string :as string]
   #?@(:cljs [[goog.object :as gobj]
              [goog.storage.mechanism.mechanismfactory :as storage-factory]
              [goog.userAgent.product :as product]]
       :clj [[clojure.data.json :as json]
             [clojure.set :as set]
             [clojure.edn :as edn]
             [cljs.repl]
             [cljs.stacktrace]
             [clojure.string :as string]
             [org.httpkit.server :as hkit #_:refer #_[run-server with-channel on-close on-receive send! open?]]])

   )
  (:import

   #?@(:cljs [goog.net.WebSocket
              goog.Promise
              [goog.storage.mechanism HTML5SessionStorage]]
       :clj [java.util.concurrent.ArrayBlockingQueue])
   )
  )


#?(:cljs (do
           (enable-console-print!)

           (def state (atom {}))
           (def storage (storage-factory/createHTML5SessionStorage "figwheel.repl"))
           (defn session-name []
             (.get storage (str ::session-name)))

           (defn response-for [{:keys [uuid]} response-body]
             {:uuid uuid
              :session-name (session-name)
              :response response-body})

           (defn respond-to [{:keys [websocket] :as old-msg} response-body]
             (let [response (response-for old-msg response-body)]
               (.send websocket (pr-str response))))

           (defmulti message :op)
           (defmethod message "naming" [msg]
             (.set storage (str ::session-name) (:session-name msg)))

           (def ^:dynamic *eval-js* (fn [code] (js* "eval(~{code})")))

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

           (defn connect [websocket-url]
             (let [websocket (goog.net.WebSocket.)]
               (doto websocket
                 (.addEventListener goog.net.WebSocket.EventType.MESSAGE
                                    (fn [e]
                                      (when-let [msg (gobj/get e "message")]
                                        (try
                                          (prn msg)
                                          (js/console.log msg)
                                          (message (assoc
                                                    (js->clj (js/JSON.parse msg) :keywordize-keys true)
                                                    :websocket websocket))
                                          (catch js/Error e
                                            (js/console.error e))))))
                 (.addEventListener goog.net.WebSocket.EventType.OPENED
                                    (fn [e]
                                      (js/console.log "OPENED")
                                      (js/console.log e)))
                 (.open websocket-url)
                 )))
           (js/console.log (.get storage (str ::session-name)))
           (js/console.log (nil? (.get storage (str ::session-name))))
           (connect (str "ws://localhost:9500/fargot" (when-let [n (.get storage (str ::session-name))]
                                                        (str "?figwheelReplSessionName=" n))))

           ))

#?(:clj (do

#_(defonce server-id (subs (str (java.util.UUID/randomUUID)) 0 6))

;; Copying the tap pattern from clojure.core
(defonce ^:private listener-set (atom #{}))
#_(defonce ^:private ^java.util.concurrent.ArrayBlockingQueue messageq (java.util.concurrent.ArrayBlockingQueue. 1024))
(defn add-listener [f] (swap! listener-set conj f) nil)
(defn remove-listener [f] (swap! listener-set disj f) nil)
#_(defn message> [x] (.offer messageq x))

(declare name-list)

(defn log [& args]
  (spit "server.log" (apply prn-str args) :append true))

(defonce scratch (atom {}))

(def ^:dynamic *connections* (atom {}))
(def ^:dynamic *server* nil)

(defn parse-query-string [qs]
  (when (string? qs)
    (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" qs)]
               [(keyword k) v]))))

(defn taken-names [connections]
  (set (mapv :session-name (vals connections))))

(defn available-names [connections]
  (set/difference name-list (taken-names connections)))

(defn negotiate-name [ring-request connections]
  (or (when-let [chosen-name (:figwheelReplSessionName (parse-query-string (:query-string ring-request)))]
        (when-not ((taken-names connections) chosen-name)
          chosen-name))
      (rand-nth (seq (available-names connections)))))

(defn async-handler [ring-request]
  (hkit/with-channel ring-request channel
    (if (hkit/websocket? channel)
      (do ;; TODO dev only
          (swap! scratch assoc :ring-request ring-request)
          (hkit/on-close
           channel
           (fn [status] (swap! *connections* dissoc channel)))
          (hkit/on-receive
           channel
           (fn [data]
             (when-let [data
                        (try (edn/read-string data)
                             (catch Throwable t (binding [*out* *err*] (clojure.pprint/pprint (Throwable->map t)))))]
               (doseq [f @listener-set]
                 (try (f (assoc data :channel channel)) (catch Throwable ex))))))
          (let [session-name (negotiate-name ring-request @*connections*)]
            (swap! *connections* assoc channel
                   (assoc (select-keys ring-request [:server-port :scheme :uri :server-name :query-string :request-method])
                          :session-name session-name
                          :created-at (System/currentTimeMillis)))
            (hkit/send! channel (json/write-str {:op :naming :session-name session-name}))))
      (hkit/send! channel {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body    "Figwheel REPL Websocket Server"}))))

;; ---------------------------------------------------
;; ReplEnv implmentation
;; ---------------------------------------------------

(defn connections-available [repl-env]
  (sort-by
   #(-> % second :created-at) >
   (filter (or (some-> repl-env :connection-filter deref)
               identity)
           @*connections*)))

(defn wait-for-connection [repl-env]
  (loop []
    (when (empty? (connections-available repl-env))
      (Thread/sleep 500)
      (recur))))

(defn send-for-eval [connections js]
  (let [prom (promise)]
    (doseq [[channel channel-data] connections]
      (let [uuid (str (java.util.UUID/randomUUID))
            listener (fn listen [msg]
                       (when (= uuid (:uuid msg))
                         (when-let [result (:response msg)]
                           (deliver prom (vary-meta
                                          result
                                          assoc ::message msg)))
                         (remove-listener listen)))]
        (add-listener listener)
        (hkit/send! ;; if we abstract this we can use other servers
         channel
         (json/write-str {:uuid uuid
                          :session-name (:session-name channel-data)
                          :op :eval
                          :code js}))))
    prom))

(let [timeout-val (Object.)]
  (defn evaluate [{:keys [focus-session-name ;; just here for consideration
                          repl-timeout
                          broadcast] :as repl-env} js]
    ;; TODO if focus session is set
    ;; if it is available send eval to it otherwise
    ;; choose next best session and send message to it
    ;; and the set the focus session to the new session name
    (wait-for-connection repl-env)
    (let [connections (connections-available repl-env)
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

(defrecord FigwheelReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (when (and
           (or (not (bound? #'*server*))
               (nil? *server*))
           (nil? @(:server-kill this)))
      (let [server-kill (hkit/run-server (bound-fn [ring-req] (async-handler ring-req))
                                         (select-keys this [:ip :port :thread :worker-name-prefix :query-size :max-body :max-line]))]
        (reset! (:server-kill this) server-kill)))
    (wait-for-connection this))
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

(defn repl-env* [{:keys [port worker-name-prefix connection-filter ring-handler]
                  :or {connection-filter identity
                       port 9500
                       worker-name-prefix "figwh-worker-"} :as opts}]
  (merge (FigwheelReplEnv.)
         {:server-kill (atom nil)
          :connection-filter (atom connection-filter)
          :focus-session-name (atom nil)
          :broadcast false
          :port port
          :worker-name-prefix worker-name-prefix}
         opts))

(comment
  (def re (repl-env* {}))
  (cljs.repl/-setup re {})
  (cljs.repl/-tear-down re)

  (connections-available re)

  (evaluate re "1")
  *connections*
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
