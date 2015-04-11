(ns figwheel-sidecar.repl
  (:require
   [cljs.repl]
   [cljs.util]
   [cljs.analyzer :as ana]   
   [cljs.env :as env]
   [clojure.stacktrace :as trace]
   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [cemerick.pomegranate :refer [add-dependencies]]
   [clojure.tools.nrepl.server :as nrepl-serv]
   [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-eval]
   [cider.nrepl :as cider]
   [cemerick.piggieback :as pback]
   
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.auto-builder :as autobuild]
   [clojurescript-build.core :as cbuild]   
   [clojurescript-build.auto :as auto]))

;; chrome error
;;  at error_test2 (http://localhost:3449/js/out/figwheel/client.js?zx=c852wj4xz1qe:384:8)
;; node error
;;  at error_test2 (/Users/brucehauman/workspace/noderer/out/noderer/core.js:16:8)
;; safari 
;;  error_test2@http://localhost:3449/js/out/figwheel/client.js:384:11
;; firefox is the same
;;  error_test2@http://localhost:3449/js/out/figwheel/client.js:384:1

;; canonical error form
;; error_test2@http://localhost:3449/js/out/figwheel/client.js:384:11

(def ^:dynamic *autobuild-env* false)

;; slow but works
;; TODO simplify in the future
(defn resolve-repl-println []
  (let [opts (resolve 'cljs.repl/*repl-opts*)]
    (or (and opts (:print @opts))
        println)))

(defn repl-println [& args]
  (apply (resolve-repl-println) args))

(defn at-start-line->canonical-stack-line [line]
  (let [[_ function file-part] (re-matches #"\s*at\s*(\S*)\s*\((.*)\)" line)]
    (str function "@" file-part)))

(defn to-canonical-stack-line [line]
  (if (re-matches #"\s*at\s*.*" line)
    (at-start-line->canonical-stack-line line)
    line))

(defn output-dir-relative-file [base-path file]
  (let [short (string/replace-first file base-path "")]
    (first (string/split short #"\?"))))

(defn stack-line->stack-line-map
  [base-path stack-line]
  (let [stack-line (to-canonical-stack-line stack-line)
        [function file line column]
        (rest (re-matches #"(.*)@(.*):([0-9]+):([0-9]+)"
                stack-line))]
    (when (and file function line column)
      { :file      (output-dir-relative-file base-path file)
        :function  function
        :line      (Long/parseLong line)
        :column    (Long/parseLong column) })))

(defn stack-line? [l]
  (and
   (map? l)
   (string?  (:file l))
   (string?  (:function l))
   (integer? (:line l))
   (integer? (:column l))))

(defn handle-stack-trace [base-path stk-str]
  (let [stk-tr (string/split-lines stk-str)
        grouped-lines (group-by stack-line? (mapv (partial stack-line->stack-line-map base-path)
                                                  stk-tr))]
    (if (< (count (grouped-lines true))
           (count (grouped-lines nil)))
      (string/join "\n" stk-tr)
      (vec (grouped-lines true)))))

(defn eval-js [{:keys [browser-callbacks output-dir] :as figwheel-server} js]
  (let [callback-name (str (gensym "repl_eval_"))
        out (chan)
        callback (fn [result]
                   (swap! browser-callbacks dissoc callback-name)
                   (put! out result)
                   (go
                     (<! (timeout 2000))
                     (close! out)))]
    (swap! browser-callbacks assoc callback-name callback)
    (fig/send-message! figwheel-server :repl-eval {:code js :callback-name callback-name})
    (let [[v ch] (alts!! [out (timeout 8000)])]
      (if (= ch out)
        v
        {:status :exception
         :value "Eval timed out!"
         :stacktrace "No stacktrace available."}))))

(defn connection-available?
  [connection-count build-id]
  (not
   (zero?
    (+ (or (get @connection-count build-id) 0)
       (or (get @connection-count nil) 0)))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [connection-count build-id]}]
  (when-not (connection-available? connection-count build-id)
    (loop []
      (when-not (connection-available? connection-count build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks]}]
  (let [pr-fn (resolve-repl-println)]
    (swap! browser-callbacks assoc "figwheel-repl-print"
           (fn [args] (apply pr-fn args)))))

(defrecord FigwheelEnv [figwheel-server]
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server js))
      ;; this is not used for figwheel
  (-load [this ns url] true)
  (-tear-down [_] true)
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [repl-env stacktrace error build-options]
    (handle-stack-trace (:base-path error) (:stacktrace error)))
  cljs.repl/IPrintStacktrace
  (-print-stacktrace [repl-env stacktrace error build-options]
    (doseq [{:keys [function file url line column]}
              (cljs.repl/mapped-stacktrace stacktrace build-options)]
      (repl-println "\t" (str function " (" (str (or url file)) ":" line ":" column ")")))))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               (select-keys build-options [:output-dir :output-to])))
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true)
                     opts)
         figwheel-repl-env (repl-env figwheel-server build)]
     (if (thread-bound? #'nrepl-eval/*msg*)
       (cemerick.piggieback/cljs-repl
        :repl-env figwheel-repl-env
        :special-fns (or (:special-fns opts) cljs.repl/default-special-fns))
       (cljs.repl/repl* figwheel-repl-env (assoc opts :compiler-env (:compiler-env build)))))))

(defn namify [arg]
  (if (seq? arg)
    (when (= 'quote (first arg))
      (str (second arg)))
    (name arg)))

(defn make-special-fn [f]
  (fn self
    ([a b c] (self a b c nil))
    ([_ _ [_ & args] _]
     ;; are we only accepting string ids?
     (f (keep namify args)))))


(defn add-dep* [dep]
  (binding [*err* *out*]
    (add-dependencies :coordinates [dep]
                      :repositories (merge cemerick.pomegranate.aether/maven-central
                                           {"clojars" "http://clojars.org/repo"}))))

(defn add-dep
  ([a b c] (add-dep a b c nil))
  ([_ _ [_ dep] _] (add-dep* dep)))

(defn doc-help
  ([repl-env env form]
   (doc-help repl-env env form nil))
  ([repl-env env [_ sym :as form] opts]
   (if (not (symbol? sym))
     (repl-println "Must provide bare var to get documentation i.e. (doc clojure.string/join)")
     (cljs.repl/evaluate-form repl-env
                              (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                              "<cljs repl>"
                              (with-meta
                                `(cljs.repl/doc ~sym)
                                {:merge true :line 1 :column 1})
                              identity opts))))

(defn validate-build-ids [ids all-builds]
  (let [bs (set (keep :id all-builds))]
    (vec (keep #(if (bs %) % (repl-println "No such build id:" %)) ids))))

(defn get-ids [ids focus-ids all-builds]
  (or (and (empty? ids) focus-ids)
      (validate-build-ids ids all-builds)))

(defn display-focus-ids [ids]
  (when (not-empty ids)
    (repl-println "Focusing on build ids:" (string/join ", " ids))))

(defn builder-running? [state-atom]
  (not (nil? (:autobuilder @state-atom))))

(defn filter-builds* [ids focus-ids all-builds]
  (let [bs (set (get-ids ids focus-ids all-builds))]
    (filter #(bs (:id %)) all-builds)))

;; API

(defn build-once [ids]
  (let [ids (map name ids)
        builds (filter-builds* ids
                               (:focus-ids @(:state-atom *autobuild-env*))
                               (:all-builds *autobuild-env*))]
    (display-focus-ids (map :id builds))
    (mapv auto/build-once (mapv #(assoc % :reload-clj-files false)
                                builds))
    nil))

(defn clean-builds [ids]
  (let [ids (map name ids)
        builds (filter-builds* ids
                               (:focus-ids @(:state-atom *autobuild-env*))
                               (:all-builds *autobuild-env*))]
    (display-focus-ids (map :id builds))
    (mapv cbuild/clean-build (map :build-options builds))
    (repl-println "Deleting ClojureScript compilation target files.")))

(defn run-autobuilder-helper [build-ids]
  (let [{:keys [all-builds figwheel-server state-atom logfile-path output-writer error-writer]} *autobuild-env*]
    (if-let [errors (not-empty (autobuild/check-autobuild-config all-builds build-ids figwheel-server))]
      (do
        (display-focus-ids build-ids)
        (mapv repl-println errors))
      (when-not (builder-running? state-atom)
        (build-once build-ids)
        
        ;; kill some undeclared warnings, hopefully?
        (Thread/sleep 300)
        (when-let [abuild
                   (binding [*out* output-writer
                             *err* error-writer]
                     (autobuild/autobuild-ids
                      { :all-builds all-builds
                        :build-ids build-ids
                        :figwheel-server figwheel-server }))]
          (if logfile-path
            (repl-println "Started Figwheel autobuilder see:" logfile-path)
            (repl-println "Started Figwheel autobuilder"))
          (reset! state-atom { :autobuilder abuild
                               :focus-ids build-ids}))))))

(defn stop-autobuild
  ([] (stop-autobuild nil))
  ([_]
   (let [{:keys [state-atom]} *autobuild-env*]
     (if (builder-running? state-atom)
       (do
         (auto/stop-autobuild! (:autobuilder @state-atom))
         (swap! state-atom assoc :autobuilder nil)
         (repl-println "Stopped Figwheel autobuild"))
       (repl-println "Autobuild not running.")))))

(defn start-autobuild [ids]
  (let [{:keys [state-atom all-builds]} *autobuild-env*
        ids (map name ids)]
    (if-not (builder-running? state-atom)
      (when-let [build-ids' (not-empty (get-ids ids
                                                (:focus-ids @state-atom)
                                                all-builds))]
        (run-autobuilder-helper build-ids')
        nil)
      (repl-println "Autobuilder already running."))))

(defn switch-to-build [ids]
  (let [ids (map name ids)]
    (when-not (empty? ids)
      (stop-autobuild [])
      (start-autobuild ids))))

(defn reset-autobuild
  ([] (reset-autobuild nil))
  ([_]
   (let [{:keys [state-atom]} *autobuild-env*]
     (stop-autobuild [])
     (clean-builds [])
     (start-autobuild (:focus-ids @state-atom)))))

(defn status
  ([] (status nil))
  ([_]
   (let [connection-count (get-in *autobuild-env* [:figwheel-server :connection-count])]
     (repl-println "Figwheel System Status")
     (repl-println "----------------------------------------------------")
     (repl-println "Autobuilder running? :" (builder-running? (:state-atom *autobuild-env*)))
     (display-focus-ids (:focus-ids @(:state-atom *autobuild-env*)))
     (repl-println "Client Connections")
     (when connection-count
       (doseq [[id v] @connection-count]
         (repl-println "\t" (str (if (nil? id) "any-build" id) ":")
                  v (str "connection" (if (= 1 v) "" "s")))))
     (repl-println "----------------------------------------------------"))))

;; end API methods

(def repl-control-fns
  { 'stop-autobuild  stop-autobuild
    'start-autobuild start-autobuild
    'switch-to-build switch-to-build
    'reset-autobuild reset-autobuild
    'build-once      build-once
    'fig-status      status
    'clean-builds    clean-builds})

(def figwheel-special-fns 
  (let [special-fns' (into {} (map (fn [[k v]] [k (make-special-fn v)]) repl-control-fns))]
    (merge cljs.repl/default-special-fns special-fns' {'add-dep add-dep
                                                       'doc doc-help})))

(defn repl-function-docs  []
  "Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (fig-status)                    ;; displays current state of system
          (add-dep [org.om/om \"0.8.1\"]) ;; add a dependency. very experimental
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object")

(defn get-build-choice [choices]
  (let [choices (set (map name choices))]
    (loop []
      (print (str "Choose focus build for CLJS REPL (" (clojure.string/join ", " choices) ") > "))
      (flush)
      (let [res (read-line)]
        (cond
          (nil? res) false
          (choices res) res
          :else
          (do
            (println (str "Error: " res " is not a valid choice"))
            (recur)))))))

(defn initial-repl-build [all-builds build-ids]
  (first (config/narrow-builds* all-builds build-ids)))

(defn initial-build-ids [all-builds build-ids]
  (let [repl-build (initial-repl-build all-builds build-ids)]
    (or (not-empty build-ids) [(:id repl-build)])))

(defn start-repl [build]
  (let [{:keys [figwheel-server build-ids state-atom]} *autobuild-env*]
    (when-not (builder-running? state-atom)
      (start-autobuild build-ids))
    (newline)
    (print "Launching ClojureScript REPL")
    (when-let [id (:id build)] (println " for build:" id))
    (println (repl-function-docs))
    (println "Prompt will show when figwheel connects to your application")
    (repl build figwheel-server {:special-fns figwheel-special-fns})))

(defn cljs-repl
  ([] (cljs-repl nil))
  ([id]
   (let [{:keys [state-atom figwheel-server all-builds build-ids]} *autobuild-env*
         opt-none-builds (set (keep :id (filter config/optimizations-none? all-builds)))
         build-id (first (not-empty (get-ids (if id [(name id)] [])
                                             (:focus-ids @state-atom)
                                             all-builds)))
         build-id (or build-id (first build-ids))
         build (first (filter #(and
                                (opt-none-builds (:id %))
                                (= build-id (:id %)))
                             all-builds))]
     (if build
       (start-repl build)
       (if id
         (println "No such build found:" (name id))
         (println "No build found to start CLJS REPL for."))))))

;;; This will not work in an nrepl env!!!
(defn repl-switching-loop
  ([] (repl-switching-loop nil))
  ([start-build]
   (let [{:keys [all-builds]} *autobuild-env*]
     (loop [build start-build]
       (cljs-repl (:id build))
       (let [chosen-build-id (get-build-choice
                              (keep :id (filter config/optimizations-none? all-builds)))]
         (if (false? chosen-build-id)
           false ;; quit
           (let [chosen-build (first (filter #(= (name (:id %)) chosen-build-id) all-builds))]
             (recur chosen-build))))))))

(defn start-nrepl-server [figwheel-options autobuild-options]
  (when (:nrepl-port figwheel-options)
    (nrepl-serv/start-server
     :port (:nrepl-port figwheel-options)
     :handler (apply nrepl-serv/default-handler
                     (conj (map resolve cider/cider-middleware) #'pback/wrap-cljs-repl)))))

(defn create-autobuild-env [{:keys [figwheel-options all-builds build-ids]}]
  (let [logfile-path (or (:server-logfile figwheel-options) "figwheel_server.log")
        _ (config/mkdirs logfile-path)
        log-writer       (if (false? (:repl figwheel-options))
                           *out*
                           (io/writer logfile-path :append true)) 
        state-atom        (atom {:autobuilder nil
                                 :focus-ids  build-ids})
        all-builds        (mapv auto/prep-build all-builds)
        build-ids         (initial-build-ids all-builds build-ids)
        figwheel-server   (figwheel-sidecar.core/start-server figwheel-options)]
    {:all-builds all-builds
     :build-ids build-ids
     :figwheel-server figwheel-server
     :state-atom state-atom
     :output-writer log-writer
     :error-writer log-writer}))

(defn run-autobuilder [{:keys [figwheel-options all-builds build-ids] :as options}]
  (binding [*autobuild-env* (create-autobuild-env options)]
    (start-autobuild (:build-ids *autobuild-env*))
    (start-nrepl-server figwheel-options *autobuild-env*)
    (if (false? (:repl figwheel-options))
      (loop [] (Thread/sleep 30000) (recur))
      (repl-switching-loop))))
