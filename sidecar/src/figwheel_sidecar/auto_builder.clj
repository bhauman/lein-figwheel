(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as fig-repl]
   [figwheel-sidecar.config :as config]
   [cljs.repl]
   [cljs.analyzer :as ana]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.set :refer [intersection]]
   [clojure.stacktrace :as trace]
   [cljsbuild.util :as util]
   [cemerick.pomegranate :refer [add-dependencies]]
   [clojure.tools.nrepl.server :as nrepl-serv]))

(defn notify-cljs [command message]
  (when (seq (:shell command))
    (try
      (util/sh (update-in command [:shell] (fn [old] (concat old [message]))))
      (catch Throwable e
        (println (auto/red "Error running :notify-command:"))
        (clj-stacktrace.repl/pst+ e)))))

(defn notify-on-complete [{:keys [build-options parsed-notify-command]}]
  (let [{:keys [output-to]} build-options]
    (notify-cljs
     parsed-notify-command
     (str "Successfully compiled " output-to))))

(defn merge-build-into-server-state [figwheel-server {:keys [id build-options]}]
  (merge figwheel-server
         (if id {:build-id id} {})
         (select-keys build-options [:output-dir :output-to :recompile-dependents])))

(defn check-changes [figwheel-server build]
  (let [{:keys [additional-changed-ns build-options id old-mtimes new-mtimes]} build]
    (binding [cljs.env/*compiler* (:compiler-env build)]
      (fig/check-for-changes
       (merge-build-into-server-state figwheel-server build)
       old-mtimes
       new-mtimes
       additional-changed-ns))))

(defn handle-exceptions [figwheel-server {:keys [build-options exception id] :as build}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (clj-stacktrace.repl/pst+ exception)
  (fig/compile-error-occured
   (merge-build-into-server-state figwheel-server build)
   exception))

(defn warning [builder warn-handler]
  (fn [build]
    (binding [cljs.analyzer/*cljs-warning-handlers* (conj cljs.analyzer/*cljs-warning-handlers*
                                                          (warn-handler build))]
      (builder build))))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (warning
     (fn [build]
       (auto/warning-message-handler
        (partial fig/compile-warning-occured
                 (merge-build-into-server-state figwheel-server build)))))
    auto/time-build
    (auto/after auto/compile-success)
    (auto/after (partial check-changes figwheel-server))
    (auto/after notify-on-complete)
    (auto/error (partial handle-exceptions figwheel-server))
    (auto/before auto/compile-start)))

(defn autobuild* [{:keys [builds figwheel-server]}]
  (auto/autobuild*
   {:builds  builds
    :builder (builder figwheel-server)
    :each-iteration-hook (fn [_] (fig/check-for-css-changes figwheel-server))}))

(defn check-autobuild-config [all-builds build-ids figwheel-server]
  (let [builds (config/narrow-builds* all-builds build-ids)]
    (config/check-config figwheel-server builds :print-warning true)))

(defn autobuild-ids [{:keys [all-builds build-ids figwheel-server]}]
  (let [builds (config/narrow-builds* all-builds build-ids)
        errors (config/check-config figwheel-server builds :print-warning true)]
    (if (empty? errors)
      (do
        (println (str "Figwheel: focusing on build-ids ("
                      (string/join " " (map :id builds)) ")"))
        (autobuild* {:builds builds
                     :figwheel-server figwheel-server}))
      (do
        (mapv println errors)
        false))))

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

;; this is temporary until my cljs.repl changes make it to release
(defn catch-special-fn-errors [special-fn]
  (fn self
    ([a b c] (self a b c nil))
    ([a b form d]
     (try
       (special-fn a b form d)
       (catch Throwable ex
         (println "Failed to execute special function:" (pr-str (first form)))
         (trace/print-cause-trace ex 12))))))

(defn add-dep
  ([a b c] (add-dep a b c nil))
  ([_ _ [_ dep] _]
     (binding [*err* *out*]
       (add-dependencies :coordinates [dep]
                         :repositories (merge cemerick.pomegranate.aether/maven-central
                                              {"clojars" "http://clojars.org/repo"})))))

(defn doc-help
  ([repl-env env form]
   (doc-help repl-env env form nil))
  ([repl-env env [_ sym :as form] opts]
   (if (not (symbol? sym))
     (print "Must provide bare var to get documentation i.e. (doc clojure.string/join)")
     (cljs.repl/evaluate-form repl-env
                              (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                              "<cljs repl>"
                              (with-meta
                                `(cljs.repl/doc ~sym)
                                {:merge true :line 1 :column 1})
                              identity opts))))

(defn setup-control-fns [all-builds build-ids figwheel-server]
  (let [logfile-path (or (:server-logfile figwheel-server) "figwheel_server.log")
        _ (config/mkdirs logfile-path)
        log-writer        (io/writer logfile-path :append true)
        state-atom        (atom {:autobuilder nil
                                 :focus-ids  build-ids})
        validate-build-ids (let [bs (set (keep :id all-builds))]
                             (fn [ids]
                               (vec (keep #(if (bs %) % (println "No such build id:" %)) ids))))
        get-ids            (fn [ids]
                             (or (and (empty? ids)
                                      (:focus-ids @state-atom))
                                 (validate-build-ids ids)))
        display-focus-ids (fn [ids]
                            (when (not-empty ids)
                              (println "Focusing on build ids:" (string/join ", " ids))))
        builder-running? #(:autobuilder @state-atom)
        ;; we are going to take an optional id here
        filter-builds*  (fn [ids]
                          (let [bs (set (get-ids ids))]
                            (filter #(bs (:id %)) all-builds)))
        build-once*     (fn [ids]
                          (let [builds (filter-builds* ids)]
                            (display-focus-ids (map :id builds))
                            (mapv auto/build-once (mapv #(assoc % :reload-clj-files false)
                                                        builds))))        
        clean-build*    (fn [ids]
                          (let [builds (filter-builds* ids)]
                            (display-focus-ids (map :id builds))
                            (mapv cbuild/clean-build (map :build-options builds))
                            (println "Deleting ClojureScript compilation target files.")))
        run-autobuilder (fn [figwheel-server build-ids]
                          (if-let [errors (not-empty (check-autobuild-config all-builds build-ids figwheel-server))]
                            (do
                              (display-focus-ids build-ids)
                              (mapv println errors))
                            (when-not (builder-running?)
                              (build-once* build-ids)

                              ;; kill some undeclared warnings, hopefully?
                              (Thread/sleep 300)
                              (when-let [abuild
                                         (binding [*out* log-writer
                                                   *err* log-writer]
                                           (autobuild-ids
                                            { :all-builds all-builds
                                              :build-ids build-ids
                                              :figwheel-server figwheel-server }))]
                                (println "Started Figwheel autobuilder see:" logfile-path)
                                (reset! state-atom { :autobuilder abuild
                                                     :focus-ids build-ids})))))
        stop-autobuild*  (fn [_]
                           (if (builder-running?)
                             (do
                               (auto/stop-autobuild! (:autobuilder state-atom))
                               (swap! state-atom assoc :autobuilder nil)
                               (println "Stopped Figwheel autobuild"))
                             (println "Autobuild not running.")))
        start-autobuild* (fn [ids]
                           (if-not (builder-running?)
                             (when-let [build-ids' (not-empty (get-ids ids))]
                               (run-autobuilder figwheel-server build-ids'))
                             (println "Autobuilder already running.")))
        switch-to-build*     (fn [ids]
                               (when-not (empty? ids)
                                 (stop-autobuild* [])
                                 (start-autobuild* ids)))
        reset-autobuild* (fn [_]
                           (stop-autobuild* [])
                           (clean-build* [])
                           (start-autobuild* (:focus-ids @state-atom)))]
    ;; add these functions to the browser-callbacks? YES
    { 'stop-autobuild  stop-autobuild*
      'start-autobuild start-autobuild*
      'switch-to-build switch-to-build*
      'reset-autobuild reset-autobuild*
      'build-once      build-once*
      'clean-build     clean-build*}))

(defn repl-function-docs  []
  "Figwheel Controls:
          (stop-autobuild)           ;; stops Figwheel autobuilder
          (start-autobuild [id ...]) ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)   ;; switches autobuilder to different build
          (reset-autobuild)          ;; stops, cleans, and starts autobuilder
          (build-once [id ...])      ;; builds source once time
          (clean-build [id ..])      ;; deletes compiled cljs target files
          (add-dep [org.om/om \"0.8.1\"]) ;; add a dependency. very experimental
  Switch REPL build focus:
          :cljs/quit                 ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3")

(defn get-build-choice [choices]
  (let [choices (set (map name choices))]
    (loop []
      (print (str "Choose focus build for CLJS REPL (" (clojure.string/join ", " choices) ") > "))
      (flush)
      (let [res (read-line)]
        (if (choices res)
          res
          (do
            (println (str "Error: " res " is not a valid choice"))
            (recur)))))))

(defn autobuild-repl [{:keys [all-builds build-ids figwheel-server] :as opts}]
  (let [all-builds'  (mapv auto/prep-build all-builds)
        repl-build   (first (config/narrow-builds* all-builds' build-ids))
        build-ids    (or (not-empty build-ids) [(:id repl-build)]) ;; give a default build-id
        control-fns  (setup-control-fns all-builds' build-ids figwheel-server)
        special-fns  (into {} (map (fn [[k v]] [k (make-special-fn v)]) control-fns))
        special-fns  (merge cljs.repl/default-special-fns special-fns {'add-dep add-dep
                                                                       'doc doc-help})
        ;; TODO remove this when cljs.repl error catching code makes it to release
        special-fns  (into {} (map (fn [[k v]] [k (catch-special-fn-errors v)]) special-fns))]
    ((get control-fns 'start-autobuild) build-ids)
    (loop [build repl-build]
      (newline)
      (print "Launching ClojureScript REPL")
      (when-let [id (:id build)] (println " for build:" id))
      (println (repl-function-docs))
      (println "Prompt will show when figwheel connects to your application")
      
      (fig-repl/repl build figwheel-server {:special-fns special-fns})
      (println "\nStart CLJS repl on another build? (Ctrl+D to exit)")
      (let [chosen-build-id (get-build-choice
                             (keep :id (filter config/optimizations-none? all-builds')))
            chosen-build (first (filter #(= (name (:id %)) chosen-build-id) all-builds'))]
        (recur chosen-build)))))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))

(def ^:dynamic *autobuild-env* false)

(defn run-autobuilder [{:keys [figwheel-options all-builds build-ids]}]
  (let [autobuild-options { :all-builds (mapv auto/prep-build all-builds)
                            :build-ids  build-ids
                            :figwheel-server (figwheel-sidecar.core/start-server
                                              figwheel-options)}]
    ;; this is a simple headless, ciderless repl for dev, I might should add
    ;; cider in here
    (binding [*autobuild-env* autobuild-options
              *print-length* 100]
      (when (:nrepl-port figwheel-options)
        (nrepl-serv/start-server :port (:nrepl-port figwheel-options))))
    (if (= (:repl figwheel-options) false)
      (when (figwheel-sidecar.auto-builder/autobuild-ids autobuild-options)
        (loop [] (Thread/sleep 30000) (recur)))
      (figwheel-sidecar.auto-builder/autobuild-repl autobuild-options))))

(comment
  
  (def builds [{ :id "example"
                 :source-paths ["src" "../support/src"]
                 :build-options { :output-to "resources/public/js/compiled/example.js"
                                  :output-dir "resources/public/js/compiled/out"
                                  :source-map true
                                  :cache-analysis true
                                  ;; :reload-non-macro-clj-files false
                                  :optimizations :none}}])

  (def env-builds (map (fn [b] (assoc b :compiler-env
                                      (cljs.env/default-compiler-env
                                        (:compiler b))))
                        builds))

  (def figwheel-server (fig/start-server))
  
  (fig/stop-server figwheel-server)
  
  (def bb (autobuild* {:builds env-builds
                       :figwheel-server figwheel-server}))
  
  (auto/stop-autobuild! bb)

  (fig-repl/eval-js figwheel-server "1 + 1")

  (def build-options (:build-options (first builds)))
  
  (cljs.repl/repl (repl-env figwheel-server) )
)
