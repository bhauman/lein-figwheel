(ns figwheel-sidecar.core
  (:require
   [cljs.compiler]
   [compojure.route :as route]
   [compojure.core :refer [routes GET]]
   [ring.util.response :refer [resource-response]]
   [ring.middleware.cors :as cors]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* ignore-dotfiles file-filter extensions]]
   [clojure.core.async :refer [go-loop <!! <! chan put! sliding-buffer timeout]]
   [clojure.string :as string]
   [clojure.edn :as edn]
   [clojure.java.io :refer [as-file] :as io]
   [digest]
   [clojurescript-build.api :as bapi]
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

;; assumes leiningen 
(defn project-unique-id []
  (let [f (io/file "./project.clj")]
    (or (when (.exists f)
          (let [[_ name version] (-> f slurp read-string)]
            (when (and name version)
              (str name "--" version))))
        (.getCanonicalPath (io/file ".")))))

(defn read-msg [data]
  (try
    (let [msg (edn/read-string data)]
      (if (and (map? msg) (:figwheel-event msg)) msg {}))
    (catch Exception e
      (println "Figwheel: message from client couldn't be read!")
      {})))

(defn get-open-file-command [{:keys [open-file-command]} {:keys [file-name file-line]}]
  (when open-file-command
    (if (= open-file-command "emacsclient")
      ["emacsclient" "-n" (str "+" file-line) file-name] ;; we are emacs aware
      [open-file-command file-name file-line])))

(defn handle-client-msg [server-state data]
  (when data
    (let [msg (read-msg data)]
      (when-let [command (and (= "file-selected" (:figwheel-event msg))
                              (get-open-file-command server-state msg))]
        (try
          (.exec (Runtime/getRuntime) (into-array String command))
          (catch Exception e
            (println "Figwheel: there was a problem running the open file command - " command))
          )))))

(defn message* [opts msg-name data]
  (merge data
         { :msg-name msg-name 
           :project-id (:unique-id opts)}))

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

    (on-receive wschannel (fn [data] (handle-client-msg server-state data)))

    ;; Keep alive!!
    (go-loop []
             (<! (timeout 5000))
             (when (open? wschannel)
               (send! wschannel (prn-str (message* server-state :ping {})))
               (recur)))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state channel))))

(defn server
  "This is the server. It is complected and its OK. Its trying to be a basic devel server and
   also provides the figwheel websocket connection."
  [{:keys [ring-handler server-port http-server-root ring-handler] :as server-state}]
  (-> (routes
       (GET "/figwheel-ws" [] (reload-handler server-state))
       (route/resources "/" {:root http-server-root})
       (or ring-handler (fn [r] false))
       (GET "/" [] (resource-response "index.html" {:root http-server-root}))
       (route/not-found "<h1>Page not found</h1>"))
      ;; adding cors to support @font-face which has a strange cors error
      ;; super promiscuous please don't uses figwheel as a production server :)
      (cors/wrap-cors
       :access-control-allow-origin #".*"
       :access-control-allow-methods [:head :options :get])
      (run-server {:port server-port})))

(defn append-msg [q msg] (conj (take 30 q) msg))

(defn send-message! [{:keys [file-change-atom] :as st} msg-name data]
  (swap! file-change-atom append-msg
         (message* st msg-name data)))

(defn send-changed-files
  "Formats and sends a files-changed message to the file-change-atom.
   Also reports this event to the console."
  [st files]
  (send-message! st :files-changed {:files files })
  (doseq [f files]
         (println "notifying browser that file changed: " (:file f))))

(defn underscore [s] (string/replace s "-" "_"))
(defn ns-to-path [nm] (string/replace nm "." "/"))
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn get-ns-from-source-file-path
  "Takes a project relative file path and returns an underscored clojure namespace.
  .ie a file that starts with (ns example.path-finder) -> example.path_finder"
  [file-path]
  (try
    
    (when (.exists (as-file file-path))
      (with-open [rdr (io/reader file-path)]
        (-> (java.io.PushbackReader. rdr)
            read
            second
            #_name
            #_underscore)))
    (catch java.lang.RuntimeException e
      nil)))

(defn get-changed-source-file-paths
  "Provided old-mtimes and new-mtimes are maps of file paths to mtimes this function
   returns collection of files that have changed grouped by their file extentions.
   .ie { :cljs [ ... changed cljs file paths ... ] }"
  [old-mtimes new-mtimes]
  (group-by
   #(keyword (subs (second (split-ext %)) 1))
   (filter
    #(not= (get new-mtimes %)
           (get old-mtimes %))
    (set (keep identity
               (mapcat keys [old-mtimes new-mtimes]))))))

(defn get-changed-source-file-ns [old-mtimes new-mtimes]
  "Returns a list of clojure namespaces that have changed. If a .clj source file has changed
   this returns all namespaces."
  (let [changed-source-file-paths (get-changed-source-file-paths old-mtimes new-mtimes)]
    (set (keep get-ns-from-source-file-path
               (if (not-empty (:clj changed-source-file-paths))
                 ;; we have to send all files if a macro changes right?
                 ;; need to test this
                 (filter #(= ".cljs" (second (split-ext %)))
                         (keys new-mtimes))
                 (:cljs changed-source-file-paths))))))

(let [root (norm-path (.getCanonicalPath (io/file ".")))]
  (defn relativize-resource-paths
    "relativize to the local root just in case we have an absolute path"
    [{:keys [resource-paths]}]
    (mapv #(string/replace-first (norm-path %)
                                 (str (norm-path root)
                                      "/") "") resource-paths)))

(defn remove-resource-path [{:keys [http-server-root] :as state} path]
  (let [path' (norm-path path)
        rp (first (filter (fn [x] (.startsWith path' (str x "/" http-server-root)))
                          (relativize-resource-paths state)))
        to-remove (str rp "/" http-server-root)]
    (string/replace path' to-remove "")))

(defn ns-to-server-relative-path
  "Given the state and a namespace makes a path relative to the server root.
  This is a path that a client can request. A caveat is that only works on
  compiled javascript namespaces."
  [{:keys [output-dir] :as state} ns]
  (let [path (.getPath (bapi/cljs-target-file-from-ns output-dir ns))]
    (remove-resource-path state path)))

(defn make-serve-from-display [{:keys [http-server-root] :as opts}]
  (let [paths (relativize-resource-paths opts)]
    (str "(" (string/join "|" paths) ")/" http-server-root)))

(defn file-changed?
  "Standard md5 check to see if a file actually changed."
  [{:keys [file-md5-atom]} filepath]  
  (let [file (as-file filepath)]
    (when (.exists file)
      (let [check-sum (digest/md5 file)
            changed? (not= (get @file-md5-atom filepath)
                           check-sum)]
        (swap! file-md5-atom assoc filepath check-sum)
        changed?))))

(defn dependency-files [{:keys [output-to output-dir]}]
   [output-to (str output-dir "/goog/deps.js")])

(defn get-dependency-files
  "Handling dependency files is different they don't have namespaces and their mtimes
   change on every compile even though their content doesn't. So we only want to include them
   when they change. This returns map representations that are ready to be sent to the client."
  [st]
  (keep
   #(when (file-changed? st %)
      { :dependency-file true
        :file (remove-resource-path st %)})
   (dependency-files st)))

(defn make-sendable-file
  "Formats a namespace into a map that is ready to be sent to the client."
  [st nm]
  (let [n (-> nm name underscore)]
    { :file (ns-to-server-relative-path st n)
      :namespace (cljs.compiler/munge n)
      :meta-data (meta nm)}
    ))


;; I would love to just check the compiled javascript files to see if
;; they changed and then just send them to the browser. There is a
;; great simplicity to that strategy. But unfortunately we can't speak
;; for the reloadability of 3rd party libraries. For this reason I am
;; only realoding files that are in the scope of the current project.

;; I also treat the 'goog.addDependancy' files as a different case.
;; These are checked for explicit changes and sent only when their
;; content changes.

;; This is the main API it is currently highly influenced by cljsbuild
;; expect this to change soon

;; after reading finally reading the cljsbuild source code, it is
;; obvious that I was doing way to much work here.

(defn notify-cljs-ns-changes [state ns-syms]
  (->> ns-syms
       (map (partial make-sendable-file state))
       (concat (get-dependency-files state))
       (send-changed-files state)))

;; this functionality should be moved to autobuilder or a new ns
;; this ns should just be for notifications?
(defn check-for-changes
  "This is the main api it should be called when a compile run has completed.
   It takes the current state of the system and a couple of mtime maps of the form
   { file-path -> mtime }.
   If changed has occured a message is appended to the :file-change-atom in state.
   Consumers of this info can add a listener to the :file-change-atom."
  ;; this is the old way which loads all local files if a clj file changes
  ([state old-mtimes new-mtimes]
     (notify-cljs-ns-changes state
                             (get-changed-source-file-ns old-mtimes new-mtimes)))
  ;; this is the new way where if additional changes are needed they
  ;; are made explicitely
  ([state old-mtimes new-mtimes additional-ns]
     (notify-cljs-ns-changes state
      (set (concat additional-ns
              (keep get-ns-from-source-file-path
                    (:cljs (get-changed-source-file-paths old-mtimes new-mtimes))))))))

;; css changes
;; this can be moved out of here I think
;; TODO we don't need to use watchtower anymore

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

(defn make-css-file [state path]
  { :file (remove-resource-path state path)
    :type :css } )

(defn send-css-files [st files]
  (send-message! st :css-files-changed { :files files })
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

(defn compile-error-occured [st exception]
  (let [parsed-exception (parse-exception exception)
        formatted-exception (let [out (java.io.ByteArrayOutputStream.)]
                              (pst-on (io/writer out) false exception)
                              (.toString out))]
    (send-message! st :compile-failed
                   { :exception-data parsed-exception
                    :formatted-exception formatted-exception })))

(defn compile-warning-occured [st msg]
  (send-message! st :compile-warning { :message msg }))

(defn initial-check-sums [state]
  (doseq [df (dependency-files state)]
    (file-changed? state df))
  (:file-md5-atom state))

(defn create-initial-state [{:keys [root name version resource-paths
                                    css-dirs ring-handler http-server-root
                                    server-port output-dir output-to
                                    unique-id
                                    open-file-command] :as opts}]
  ;; I'm spelling this all out as a reference
  { :unique-id (or unique-id (project-unique-id)) 
   
    :resource-paths (or resource-paths ["resources"])
    :css-dirs css-dirs
    :http-server-root (or http-server-root "public")
    :output-dir output-dir
    :output-to output-to
    :ring-handler ring-handler
    :server-port (or server-port 3449)
    
    :css-last-pass (atom (System/currentTimeMillis))   
    :compile-wait-time 10
    :file-md5-atom (initial-check-sums {:output-to output-to
                                        :output-dir output-dir
                                        :file-md5-atom (atom {})})
    :file-change-atom (atom (list))
    :open-file-command open-file-command
   })

(defn resolve-ring-handler [{:keys [ring-handler] :as opts}]
  (when ring-handler (require (symbol (namespace (symbol ring-handler)))))
  (assoc opts :ring-handler
         (when ring-handler
           (eval (symbol ring-handler)))))

(defn start-server [opts]
  (let [state (create-initial-state (resolve-ring-handler opts))]
    (println (str "Figwheel: Starting server at http://localhost:" (:server-port state)))
    (println (str "Figwheel: Serving files from '"
                  (make-serve-from-display state) "'"))
    (assoc state :http-server (server state))))

(defn stop-server [{:keys [http-server]}]
  (http-server))
