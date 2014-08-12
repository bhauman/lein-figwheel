(ns figwheel.core
  (:require
   [cljs.compiler]
   [compojure.route :as route]
   [compojure.core :refer [routes GET]]
   [ring.util.response :refer [resource-response]]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* ignore-dotfiles file-filter extensions]]
   [clojure.core.async :refer [go-loop <!! <! chan put! sliding-buffer timeout]]
   [clojure.string :as string]

   [clojure.java.io :refer [as-file] :as io]
   [digest]
   [clojure.set :refer [intersection]]
   [clojure.java.io :as io]
   [clj-stacktrace.core :refer [parse-exception]]
   [clj-stacktrace.repl :refer [pst-on]]
   [clojure.pprint :as p]))

;; get rid of fs dependancy

(defn split-ext
  "Returns a vector of `[name extension]`."
  [path]
  (let [base (.getName (io/file path))
        i (.lastIndexOf base ".")]
    (if (pos? i)
      [(subs base 0 i) (subs base i)]
      [base nil])))

(defn setup-file-change-sender [{:keys [file-change-atom compile-wait-time] :as server-state}
                                wschannel]
  (let [watch-key (keyword (gensym "message-watch-"))]
    (add-watch file-change-atom
               watch-key
               (fn [_ _ o n]
                 (let [msg (first n)]
                   (when msg
                     (<!! (timeout compile-wait-time))
                     (when (open? wschannel)
                       (send! wschannel (prn-str msg)))))))
    
    (on-close wschannel (fn [status]
                          (remove-watch file-change-atom watch-key)
                          (println "Figwheel: client disconnected " status)))
    
    ;; Keep alive!!
    (go-loop []
             (<! (timeout 5000))
             (when (open? wschannel)
               (send! wschannel (prn-str {:msg-name :ping}))
               (recur)))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state channel))))

(defn server [{:keys [ring-handler server-port http-server-root ring-handler] :as server-state}]
  (run-server
   (routes
    (GET "/figwheel-ws" [] (reload-handler server-state))
    (route/resources "/" :root http-server-root)
    (or ring-handler (fn [r] false))
    (GET "/" [] (resource-response "index.html" {:root http-server-root}))
    (route/not-found "<h1>Page not found</h1>"))
   {:port server-port}))

(defn append-msg [q msg]
  (conj (take 30 q) msg))

(defn make-msg [file-data]
  (merge { :type :javascript
           :msg-name :file-changed }
         file-data))

(defn send-changed-files [{:keys [file-change-atom] :as st} files]
  (swap! file-change-atom append-msg { :msg-name :files-changed
                                       :files (mapv make-msg files)})
  (doseq [f files]
         (println "notifying browser that file changed: " (:file f))))

(defn underscore [st]
  (string/replace st "-" "_"))

(defn ns-to-path [nm]
  (string/join "/" (string/split nm #"\.")))

(defn path-to-ns [path]
  (string/join "." (string/split path #"\/")))

(defn get-ns-from-js-file-path [state file-path]
  (-> file-path
      (string/replace "\\" "/")
      (string/replace-first (str (:output-dir state) "/") "")
      (string/replace-first #"\.js$" "")
      path-to-ns
      underscore))

(defn get-ns-from-source-file-path [file-path]
  (try
    (when (.exists (as-file file-path))
      (with-open [rdr (io/reader file-path)]
        (-> (java.io.PushbackReader. rdr)
            read
            second
            name
            underscore)))
    (catch java.lang.RuntimeException e
      nil)))

(defn get-changed-source-file-paths [old-mtimes new-mtimes]
  (group-by
   #(keyword (subs (second (split-ext %)) 1))
   (filter
    #(not= (get new-mtimes %)
           (get old-mtimes %))
    (set (keep identity
               (mapcat keys [old-mtimes new-mtimes]))))))

(defn resource-paths [{:keys [root resource-paths]}]
  (mapv #(string/replace-first % (str root "/") "") resource-paths))

(defn resource-paths-pattern-str [state]
  (str "(" (string/join "|" (resource-paths state)) ")"
        "/" (:http-server-root state)))

(def resource-paths-pattern (comp re-pattern resource-paths-pattern-str))

(defn server-relative-root-path [state]
  (string/replace-first
   (:output-dir state)
   (resource-paths-pattern state) ""))

(defn make-server-relative-path [state nm]
  (str
   (server-relative-root-path state)
   "/" (ns-to-path nm) ".js"))

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
        :file (string/replace-first % (resource-paths-pattern st) "")})
   (dependency-files st)))

(defn make-sendable-file [st path]
  { :file (make-server-relative-path st path)
    :namespace (cljs.compiler/munge path)})

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

;; I would love to just check the compiled javascript files to see if
;; they changed and then just send them to the browser. There is a
;; great simplicity to that strategy. But unfortunately we can't speak
;; for the reloadability of 3rd party libraries. For this reason I am
;; only realoding files that are in the scope of the current project.

;; I also treat the 'goog.addDependancy' files as a different case.
;; These are checked for explicit changes and sent only when their
;; content changes.

(defn check-for-changes [state old-mtimes new-mtimes]
  (when-let [changed-compiled-ns (get-changed-compiled-namespaces state)]
    (let [changed-source-file-paths (get-changed-source-file-paths old-mtimes new-mtimes)
          changed-source-file-ns (set (keep get-ns-from-source-file-path
                                            (if (not-empty (:clj changed-source-file-paths))
                                              (filter #(= ".cljs" (second (split-ext %)))
                                                      (keys new-mtimes))
                                              (:cljs changed-source-file-paths))))
          changed-project-ns (intersection changed-compiled-ns changed-source-file-ns)
          sendable-files (map (partial make-sendable-file state) changed-project-ns)
          files-to-send  (concat (get-dependency-files state) sendable-files)]
      (send-changed-files state files-to-send))))

;; css changes

;; watchtower css file change detection
(defn compile-css-filewatcher [{:keys [css-dirs] :as server-state}]
  (compile-watcher (-> css-dirs
                       (watcher*)
                       (file-filter ignore-dotfiles)
                       (file-filter (extensions :css)))))

(defn get-changed-css-files [{:keys [last-pass css-last-pass] :as state}]
  ;; this uses watchtower change detection
  (binding [wt/*last-pass* css-last-pass]
    (let [{:keys [updated?]} (compile-css-filewatcher state)]
      (map (fn [x] (.getPath x)) (updated?)))))

(defn make-server-relative-css-path [state nm]
  (string/replace-first nm (resource-paths-pattern state) ""))

(defn make-css-file [state path]
  { :file (make-server-relative-css-path state path)
    :type :css } )

(defn send-css-files [{:keys [file-change-atom]} files]
  (swap! file-change-atom append-msg { :msg-name :css-files-changed
                                      :files files})
  (doseq [f files]
    (println "sending changed CSS file:" (:file f))))

(defn check-for-css-changes [state]
  (when (:css-dirs state)
    (let [changed-css-files (get-changed-css-files state)]
      (when (not-empty changed-css-files)
        (send-css-files state (map (partial make-css-file state) 
                                   changed-css-files))))))

;; end css changes

;; compile error occured

(defn compile-error-occured [{:keys [file-change-atom]} exception]
  (let [parsed-exception (parse-exception exception)
        formatted-exception (let [out (java.io.ByteArrayOutputStream.)]
                              (pst-on (io/writer out) false exception)
                              (.toString out))]
      (swap! file-change-atom append-msg { :msg-name :compile-failed
                                           :exception-data parsed-exception
                                           :formatted-exception formatted-exception })))

(defn initial-check-sums [state]
  (doseq [df (dependency-files state)]
    (file-changed? state df))
  (:file-md5-atom state))

(defn create-initial-state [{:keys [root resource-paths
                                    js-dirs css-dirs ring-handler http-server-root
                                    server-port output-dir output-to]}]
  { :root root
    :resource-paths resource-paths
    :css-dirs css-dirs
    :js-dirs js-dirs
    :http-server-root (or http-server-root "public")
    :output-dir output-dir
    :output-to output-to
    :ring-handler ring-handler
    :server-port (or server-port 3449)
    :last-pass (atom (System/currentTimeMillis))
    :css-last-pass (atom (System/currentTimeMillis))   
    :compile-wait-time 10
    :file-md5-atom (initial-check-sums {:output-to output-to
                                        :output-dir output-dir
                                        :file-md5-atom (atom {})})
    :file-change-atom (atom (list))})

(defn resolve-ring-handler [{:keys [ring-handler] :as opts}]
  (when ring-handler (require (symbol (namespace (symbol ring-handler)))))
  (assoc opts :ring-handler
         (when ring-handler
           (eval (symbol ring-handler)))))

(defn start-server [opts]
  (let [state (create-initial-state (resolve-ring-handler opts))]
    (println (str "Figwheel: Starting server at http://localhost:" (:server-port state)))
    (println (str "Figwheel: Serving files from '" (resource-paths-pattern-str state) "'"))
    (assoc state :http-server (server state))))

(defn stop-server [{:keys [http-server]}]
  (http-server))
