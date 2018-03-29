(ns figwheel.repl
  (:require
   #?@(:cljs [[goog.object :as gobj]
              [goog.storage.mechanism.mechanismfactory :as storage-factory]]
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

           (defmulti message :op)
           (defmethod message "naming" [msg]
             (.set storage (str ::session-name) (:session-name msg)))

           (defn connect [websocket-url]
             (doto (goog.net.WebSocket.)
               (.addEventListener goog.net.WebSocket.EventType.MESSAGE
                                  (fn [e]
                                    (when-let [msg (gobj/get e "message")]
                                      (try
                                        (prn msg)
                                        (message (js->clj (js/JSON.parse msg) :keywordize-keys true))
                                        (catch js/Error e
                                          (js/console.error e))))))
               (.addEventListener goog.net.WebSocket.EventType.OPENED
                                  (fn [e]
                                    (js/console.log "OPENED")
                                    (js/console.log e)))
               (.open websocket-url)
               ))
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
      (let [uuid (java.util.UUID/randomUUID)
            listener (fn listen [msg]
                       (when (= uuid (:uuid msg))
                         (when-let [result (:result msg)]
                           (deliver prom (vary-meta
                                          result
                                          assoc ::message msg)))
                         (remove-listener listen)))]
        (add-listener listener)
        (hkit/send!
         channel
         (json/write-str {:uuid uuid
                          :session-name (:session-name channel-data)
                          :op :eval
                          :code js}))))
    prom))

(let [timeout-val (Object.)]
  (defn evaluate [{:keys [focus-session-name ;; just here for consideration
                          last-session-name
                          repl-timeout
                          broadcast] :as repl-env} js]
    ;; TODO if focus session is set
    ;; if it is available send eval to it otherwise
    ;; choose next best session and send message to it
    ;; and the set the focus session to the new session name
    (wait-for-connection)
    (let [connections (connections-available repl-env)
          youngest-connection (first connections)
          result (let [v (deref (send-for-eval (if broadcast
                                                 connections
                                                 [youngest-connection])
                                               js)
                                (or repl-timeout 8000)
                                timeout-val)]
                   (some->> v
                            meta
                            ::message
                            :session-name
                            (reset! last-session-name))
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
    (binding [*connections* (:connections this)]
      (wait-for-connection (get this :connection-filter))))
  (-evaluate [this _ _  js] (evaluate this js))
  (-load [this provides url]
    ;; load a file into all the appropriate envs
    (when-let [js-content (try (slurp url) (catch Throwable t))]
      (evaluate (assoc repl-env :broadcast true) js-content)))
  (-tear-down [this])
  cljs.repl/IReplEnvOptions
  (-repl-options [this])
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (cljs.stacktrace/parse-stacktrace this st err opts)))

(defn repl-env* [{:keys [host port] :or {host "localhost" port 3449} :as opts}]
  (merge (FigwheelReplEnv.)
         ;; TODO choose-connections not connection filter
         {:connection-filter (atom (or connection-filter identity))
          :focus-session-name (atom nil)
          :last-session-name (atom nil)
          :broadcast false}
         opts))

;; these are only for development right now
(defonce servers (atom []))

(defn start-server []
  (swap! servers conj (hkit/run-server async-handler {:port 9500})))

(defn stop-server []
  (doseq [srv @servers]
    (srv :timeout 100))
  (reset! servers []))



(comment
  (start-server)
  (stop-server)

  *connections*
  (mapv hkit/open? (keys @connections))
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
