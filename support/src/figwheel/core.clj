(ns figwheel.core
  (:require
   [compojure.route :refer [files not-found] :as route]
   [compojure.handler :refer [site api]] ; form, query params decode; cookie; session, etc
   [compojure.core :refer [defroutes GET POST DELETE ANY context routes]]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* rate ignore-dotfiles file-filter extensions on-change]]
   [clojure.core.async :refer [go-loop <!! chan put! sliding-buffer timeout map< mult tap close!]]
   [clojure.string :as string]
   [fs.core :as fs]))

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

(defn send-changed-file [{:keys [file-change-atom] :as st} filename]
  (println "sending changed file:" filename)
  (swap! file-change-atom
         append-msg
         {:msg-name :file-changed
          :type :javascript
          :file filename}))

(defn send-changed-files [server-state files]
  (mapv (partial send-changed-file server-state) files))

(defn compile-js-filewatcher [{:keys [js-dirs] :as server-state}]
  (compile-watcher (-> js-dirs
                       (watcher*)
                       (file-filter ignore-dotfiles)
                       (file-filter (extensions :js)))))

(defn make-base-js-file-path [state file-paths]
  (map (fn [p]
         (if (= p (:output-to state))
           (-> p
               (string/replace-first (str "resources/" (:http-server-root state) "/") "")
               (string/replace-first #"\.js$" ""))
           (-> p
               (string/replace-first (str (:output-dir state) "/") "")
               (string/replace-first #"\.js$" "")))) 
       file-paths))

(defn make-base-cljs-file-path [file-paths]
  (map #(string/replace-first % #"\.cljs$" "") 
       file-paths))

(defn get-changed-dependencies [state js-file-paths cljs-file-paths]
  (let [js-base (make-base-js-file-path state js-file-paths)
        cljs-base (make-base-cljs-file-path cljs-file-paths)]
    (filter (fn [es]  (some (fn [x] (.endsWith x es)) cljs-base)) js-base)))

(defn get-changed-file-paths [old-mtimes new-mtimes]
  (filter
   (fn [k]
     (and (not= (get new-mtimes k)
                (get old-mtimes k))))
   (set (keep identity
              (mapcat keys [old-mtimes new-mtimes])))))

(defn make-server-relative-path [state path]
  (str (string/replace-first (:output-dir state)
                             (str "resources/" (:http-server-root state)) "")
       "/" path ".js"))

(defn add-main-file [state js-paths]
  (cons
   (string/replace-first (:output-to state)
                         (str "resources/" (:http-server-root state)) "")
   js-paths))

;; this is a simpler alternative
(defn or-just-send-all-the-changed-files [state changes]
  (send-changed-files
   state
   (add-main-file
    state
    (map (partial make-server-relative-path state)
         (make-base-js-file-path state
                                 (filter
                                  (fn [x] (not= x (:output-to state)))
                                  (map (fn [x] (.getPath x))
                                       changes)))))))

;; this narrows the files being sent to files that are in the project
;; still no where near as good as getting a dependancy graph and
;; pushing the files that are actually needed
(defn check-for-changes [{:keys [last-pass js-dirs] :as state} old-mtimes new-mtimes]
  (binding [wt/*last-pass* last-pass]
    (let [{:keys [updated? changed]} (compile-js-filewatcher state)]
      (when-let [changes (updated?)]
        ;; not super happy with this but it is a way to decern
        ;; relevant changes with locally available path information
        (let [changed-file-paths (get-changed-file-paths old-mtimes new-mtimes)
              changed-clj-file-paths (filter #(= ".clj" (second (fs/split-ext %))) changed-file-paths)
              changed-cljs-file-paths (filter #(= ".cljs" (second (fs/split-ext %))) changed-file-paths)
              changed-dependencies (get-changed-dependencies state
                                                             (map (fn [x] (.getPath x)) changes)
                                                             (if (not-empty changed-clj-file-paths)
                                                               (keys new-mtimes) ; reload all relevant files if clj changed
                                                               changed-cljs-file-paths))
              changed-js-sr-paths (map (partial make-server-relative-path state)
                                       changed-dependencies)]
          (send-changed-files state changed-js-sr-paths))))))

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
