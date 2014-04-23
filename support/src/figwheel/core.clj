(ns figwheel.core
  (:require
   [compojure.route :refer [files not-found] :as route]
   [compojure.handler :refer [site api]] ; form, query params decode; cookie; session, etc
   [compojure.core :refer [defroutes GET POST DELETE ANY context routes]]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* rate ignore-dotfiles file-filter extensions on-change]]
   [clojure.core.async :refer [go-loop <!! chan put! sliding-buffer timeout map< mult tap close!]]
   [clojure.string :as string]
   [fs.core :as fs]
   [clojure.java.io :refer [as-file]]
   [digest]
   [clojure.pprint :as p]))

(defn setup-file-change-sender [{:keys [file-change-atom compile-wait-time] :as server-state}
                                wschannel]
  (add-watch file-change-atom
             :message-watch
             (fn [_ _ o n]
               (let [msg (first n)]
                 (when msg
                   (<!! (timeout compile-wait-time))
                   (when (open? wschannel)
                     (send! wschannel (prn-str msg)))))))
  
  ;; Keep alive!!
  (go-loop []
           (<! (timeout 5000))
           (when (open? wschannel)
             (send! wschannel (prn-str {:msg-name :ping}))
             (recur))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state channel)
      (on-close channel (fn [status]
                          (println "Figwheel: client disconnected " status))))))

(defn server [{:keys [ring-handler server-port] :as server-state}]
  (run-server
   (if ring-handler
     (routes (GET "/figwheel-ws" [] (reload-handler server-state)) ring-handler)
     (routes (GET "/figwheel-ws" [] (reload-handler server-state))))
   {:port server-port}))

(defn append-msg [q msg]
  (conj (take 30 q) msg))

(defn send-changed-file [{:keys [file-change-atom] :as st} file-data]
  (println "sending changed file:" (:file file-data))
  (swap! file-change-atom
         append-msg
         (merge { :type :javascript
                 :msg-name :file-changed }
                file-data)))

(defn send-changed-files [server-state files]
  (mapv (partial send-changed-file server-state) files))

(defn compile-js-filewatcher [{:keys [js-dirs] :as server-state}]
  (compile-watcher (-> js-dirs
                       (watcher*)
                       (file-filter ignore-dotfiles)
                       (file-filter (extensions :js)))))

(defn make-base-js-file-path [state file-path]
  (-> file-path
      (string/replace-first (str (:output-dir state) "/") "")
      (string/replace-first #"\.js$" "")))

(defn make-base-cljs-file-path [file-path]
  (string/replace-first file-path #"\.cljs$" "") )

(defn get-changed-dependencies [state js-file-paths cljs-file-paths]
  (let [js-base   (map (partial make-base-js-file-path state) js-file-paths)
        cljs-base (map make-base-cljs-file-path cljs-file-paths)]
    (filter (fn [es] (some (fn [x] (.endsWith x es)) cljs-base)) js-base)))

(defn get-changed-file-paths [old-mtimes new-mtimes]
  (filter
   #(not= (get new-mtimes %)
          (get old-mtimes %))
   (set (keep identity
              (mapcat keys [old-mtimes new-mtimes])))))

(defn make-server-relative-path [state path]
  (let [base-path (string/replace-first (:output-dir state)
                                        (str "resources/" (:http-server-root state)) "")]
    (str base-path "/" path ".js")))

(defn file-changed? [{:keys [file-md5-atom]} filepath]
  (let [file (as-file filepath)]
    (when (.exists file)
      (let [check-sum (digest/md5 file)
            changed? (not= (get @file-md5-atom filepath)
                           check-sum)]
        (swap! file-md5-atom assoc filepath check-sum)
        changed?))))

(defn dependency-files [{:keys [output-to output-dir]}]
   [output-to (str output-dir "/goog/deps.js")])

(defn get-dependency-files [{:keys [http-server-root] :as st}]
  (keep
   #(when (file-changed? st %)
      { :dependency-file true
        :file (string/replace-first % (str "resources/" http-server-root) "")})
   (dependency-files st)))

(defn make-sendable-file [st path]
  { :file (make-server-relative-path st path)
    :namespace (string/join "." (string/split path #"\/")) })

(defn check-for-changes [{:keys [last-pass js-dirs] :as state} old-mtimes new-mtimes]
  (binding [wt/*last-pass* last-pass]
    (let [{:keys [updated? changed]} (compile-js-filewatcher state)]
      (when-let [changes (updated?)]
        (let [changed-file-paths      (get-changed-file-paths old-mtimes new-mtimes)
              changed-cljs-file-paths (filter #(= ".cljs" (second (fs/split-ext %))) changed-file-paths)
              changed-dependencies    (get-changed-dependencies state
                                                                (map (fn [x] (.getPath x)) changes)
                                                                changed-cljs-file-paths)
              changed-js-sr-paths (map (partial make-sendable-file state)
                                       changed-dependencies)
              files-to-send (concat (get-dependency-files state) changed-js-sr-paths)]
          #_(p/pprint changed-js-sr-paths)
          #_(p/pprint files-to-send)
          (send-changed-files state
                              files-to-send))))))

;; to be used for css reloads
#_(defn file-watcher [state] (watcher ["./.cljsbuild-mtimes"]
                                      (rate 500) ;; poll every 500ms
                                      (on-change (fn [_] (check-for-changes state)))))

(defn create-initial-state [{:keys [root js-dirs ring-handler http-server-root ignore-cljs-libs server-port output-dir output-to]}]
  { :js-dirs js-dirs
    :http-server-root (or http-server-root "public")
    :output-dir output-dir
    :output-to output-to
    :ring-handler ring-handler
    :server-port (or server-port 8080)
    :last-pass (atom (System/currentTimeMillis))
    :compile-wait-time 10
    :file-md5-atom (atom {})
    :file-change-atom (atom (list))})

(defn start-server [{:keys [js-dirs ring-handler] :as opts}]
  (let [state (create-initial-state opts)]
    (println (str "Figwheel: Starting server at http://localhost:" (:server-port opts)))
    (println (str "Figwheel: Serving files from 'resources/" (:http-server-root state) "'"))
    (merge { :http-server (server state)
            ;; we are going to use this for css change reloads
             #_:file-change-watcher #_(file-watcher state)} 
           state)))

(defn start-static-server [{:keys [js-dirs http-server-root] :as opts}]
  (let [http-s-root (or http-server-root "public")]
    (start-server (merge opts {:ring-handler (route/resources "/" :root http-s-root)
                               :http-server-root http-s-root}))))

(defn stop-server [{:keys [http-server file-change-watcher] :as server-data}]
  (http-server)
  (when file-change-watcher
    (future-cancel file-change-watcher)))
