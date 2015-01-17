(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as fig-repl]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]
   [cljsbuild.util :as util]))

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

(defn check-changes [figwheel-server build]
  (let [{:keys [additional-changed-ns build-options id old-mtimes new-mtimes]} build]
    (fig/check-for-changes (merge figwheel-server
                                  (if id {:build-id id} {})
                                  (select-keys build-options [:output-dir :output-to]))
                           old-mtimes
                           new-mtimes
                           additional-changed-ns)))

(defn handle-exceptions [figwheel-server {:keys [build-options exception]}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (clj-stacktrace.repl/pst+ exception)
  (fig/compile-error-occured figwheel-server exception))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (auto/warning (auto/warning-message-handler
                   (partial fig/compile-warning-occured figwheel-server)))
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

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

(defn clean-build [{:keys [output-to output-dir] :as build-options}]
  (when (and output-to output-dir)
    (let [clean-file (fn [s] (when (.exists s) (.delete s)))]
      (mapv clean-file (cons (io/file output-to) (reverse (file-seq (io/file output-dir))))))))

(defn autobuild-repl [{:keys [builds figwheel-server] :as opts}]
  (let [builds' (mapv auto/prep-build
                      builds)
        logfile-path (or (:server-logfile figwheel-server) "figwheel_server.log")
        _ (mkdirs logfile-path)
        log-writer (io/writer logfile-path :append true)
        autobuilder-atom (atom nil)
        run-autobuilder (fn [figwheel-server builds]
                          (binding [*out* log-writer
                                    *err* log-writer]
                            ;; blocking build to ensure code exists before repl starts
                            (mapv (builder figwheel-server) builds)
                            (reset! autobuilder-atom (autobuild* {:builds builds
                                                                  :figwheel-server figwheel-server }))))
        build-once       (fn self
                           ([a b c] (self a b c nil))
                           ([_ _ _ _]
                            (mapv (builder figwheel-server) builds')))
        clean-build-fn   (fn self
                           ([a b c] (self a b c nil))
                           ([_ _ _ _]
                            (mapv clean-build (map :build-options builds'))))
        stop-autobuilder (fn self
                           ([a b c] (self a b c nil))
                           ([_ _ _ _]
                            (if @autobuilder-atom
                              (do
                                (auto/stop-autobuild! @autobuilder-atom)
                                (reset! autobuilder-atom nil)
                                (println "Stopped Figwheel autobuild"))
                              (println "Autobuild not running."))))
        start-autobuilder (fn self
                            ([a b c] (self a b c nil))
                            ([_ _ _ _]
                             (if-not @autobuilder-atom
                               (do
                                 (run-autobuilder figwheel-server builds')
                                 (println "Started Figwheel autobuilder see:" logfile-path ))
                               (println "Autobuilder already running."))))
        reset-autobuilder (fn self
                              ([a b c] (self a b c nil))
                              ([_ _ _ _]
                               (if @autobuilder-atom
                                 (do
                                   (auto/stop-autobuild! @autobuilder-atom)
                                   (mapv clean-build (map :build-options builds'))
                                   (run-autobuilder figwheel-server builds')
                                   (println "Restarted Figwheel autobuilder"))
                                 (println "Autobuild not running."))))
        special-fns  { 'stop-autobuild stop-autobuilder
                       'start-autobuild start-autobuilder
                       'reset-autobuild reset-autobuilder                      
                       'build-once build-once
                       'clean-build clean-build-fn}]
    
    (println "Server output being sent to logfile:" logfile-path "\n")

    (run-autobuilder figwheel-server builds')

    (if (:id (first builds'))
      (println "Launching ClojureScript REPL for build:" (:id (first builds')))
      (println "Launching ClojureScript REPL"))
    (println "Prompt will show when figwheel connects to your application")
    (fig-repl/repl (first builds') figwheel-server {:special-fns special-fns})))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))

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
