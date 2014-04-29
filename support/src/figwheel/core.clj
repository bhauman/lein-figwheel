(ns figwheel.core
  (:require
   [compojure.route :as route]
   [compojure.core :refer [routes GET]]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* ignore-dotfiles file-filter extensions]]
   [clojure.core.async :refer [go-loop <!! <! chan put! sliding-buffer timeout]]
   [clojure.string :as string]
   [fs.core :as fs]
   [clojure.java.io :refer [as-file] :as io]
   [digest]
   [clojure.set :refer [intersection]]
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

(defn underscore [st]
  (string/replace st "-" "_"))

(defn ns-to-path [nm]
  (string/join "/" (string/split nm #"\.")))

(defn path-to-ns [path]
  (string/join "." (string/split path #"\/")))

(defn get-ns-from-js-file-path [state file-path]
  (-> file-path
      (string/replace-first (str (:output-dir state) "/") "")
      (string/replace-first #"\.js$" "")
      path-to-ns
      underscore))

(defn get-ns-from-source-file-path [file-path]
  (with-open [rdr (io/reader file-path)]
    (-> (java.io.PushbackReader. rdr)
        read
        second
        name
        underscore)))

(defn get-changed-source-file-paths [old-mtimes new-mtimes]
  (group-by
   #(keyword (subs (second (fs/split-ext %)) 1))
   (filter
    #(not= (get new-mtimes %)
           (get old-mtimes %))
    (set (keep identity
               (mapcat keys [old-mtimes new-mtimes]))))))

(defn make-server-relative-path [state nm]
  (-> (:output-dir state)
      (string/replace-first (str "resources/" (:http-server-root state)) "")
      (str "/" (ns-to-path nm) ".js")))

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

;; watchtower file change detection
(defn compile-js-filewatcher [{:keys [js-dirs] :as server-state}]
  (compile-watcher (-> js-dirs
                       (watcher*)
                       (file-filter ignore-dotfiles)
                       (file-filter (extensions :js)))))

(defn get-changed-compiled-js-files [{:keys [last-pass] :as state}]
  ;; this uses watchtower change detection
  (binding [wt/*last-pass* last-pass] 
    (let [{:keys [updated?]} (compile-js-filewatcher state)]
      (map (fn [x] (.getPath x)) (updated?)))))

(defn get-changed-compiled-namespaces [state]
  (set (mapv (partial get-ns-from-js-file-path state)
             (get-changed-compiled-js-files state))))

;; I'm putting this check here and it really belongs cljsbuild
(defn hyphen-warn [{:keys [root]} paths]
  (let [bad-paths (filter #(< 1 (count (string/split % #"-")))
                          (map
                           #(string/replace-first % root "")
                           paths))]
    (when (not-empty bad-paths)
      (println "Please use underscores instead of hyphens in your directory and file names")
      (println "The following source paths have hyphens in them:")
      (p/pprint bad-paths))))

;; I would love to just check the compiled javascript files to see if
;; they changed and then just send them to the browser. There is a
;; great simplicity to that strategy. But unfortunately we can't speak
;; for the reloadability of 3rd party libraries. For this reason I am
;; only realoding files that are in the scope of the current project.

;; I also treat the 'goog.addDependancy' files as a different case.
;; These are checked for explicit changes and sent only when the
;; content changes.

(defn check-for-changes [state old-mtimes new-mtimes]
  (hyphen-warn state (keys new-mtimes))
  (when-let [changed-compiled-ns (get-changed-compiled-namespaces state)]
    (let [changed-source-file-paths (get-changed-source-file-paths old-mtimes new-mtimes)
          changed-source-file-ns (set (mapv get-ns-from-source-file-path
                                            (if (not-empty (:clj changed-source-file-paths))
                                              (filter #(= ".cljs" (second (fs/split-ext %)))
                                                      (keys new-mtimes))
                                              (:cljs changed-source-file-paths))))
          changed-project-ns (intersection changed-compiled-ns changed-source-file-ns)
          sendable-files (map (partial make-sendable-file state) changed-project-ns)
          files-to-send  (concat (get-dependency-files state) sendable-files)]
      (send-changed-files state files-to-send))))

(defn initial-check-sums [state]
  (doseq [df (dependency-files state)]
    (file-changed? state df))
  (:file-md5-atom state))

(defn create-initial-state [{:keys [root js-dirs ring-handler http-server-root server-port output-dir output-to]}]
  { :root root
    :js-dirs js-dirs
    :http-server-root (or http-server-root "public")
    :output-dir output-dir
    :output-to output-to
    :ring-handler ring-handler
    :server-port (or server-port 3449)
    :last-pass (atom (System/currentTimeMillis))
    :compile-wait-time 10
    :file-md5-atom (initial-check-sums {:output-to output-to
                                        :output-dir output-dir
                                        :file-md5-atom (atom {})})
    :file-change-atom (atom (list))})

(defn start-server [{:keys [js-dirs ring-handler] :as opts}]
  (let [state (create-initial-state opts)]
    (println (str "Figwheel: Starting server at http://localhost:" (:server-port state)))
    (println (str "Figwheel: Serving files from 'resources/" (:http-server-root state) "'"))
    (assoc state :http-server (server state))))

(defn start-static-server [{:keys [js-dirs http-server-root] :as opts}]
  (let [http-s-root (or http-server-root "public")]
    (start-server (merge opts {:ring-handler (route/resources "/" :root http-s-root)
                               :http-server-root http-s-root}))))

(defn stop-server [{:keys [http-server]}]
  (http-server))


