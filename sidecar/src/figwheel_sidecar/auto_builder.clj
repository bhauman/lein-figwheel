(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as fig-repl]
   [figwheel-sidecar.config :as config]   
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]
   [clojure.string :as string]
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

(defn autobuild-ids [{:keys [all-builds build-ids figwheel-server]}]
  (let [builds (config/narrow-builds* all-builds build-ids)
        errors (config/check-config figwheel-server builds)]
    (if (empty? errors)
      (do
        (println (str "Figwheel: focusing on build-ids ("
                      (string/join " " (map :id builds)) ")"))
        (autobuild* {:builds builds
                     :figwheel-server figwheel-server}))
      (do
        (mapv println errors)
        false))))

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

(defn wrap-special-no-args [f]
  (fn self
    ([a b c] (self a b c nil))
    ([_ _ _ _] (f))))

(defn setup-control-fns [all-builds build-ids figwheel-server]
  (let [logfile-path (or (:server-logfile figwheel-server) "figwheel_server.log")
        _ (mkdirs logfile-path)
        log-writer (io/writer logfile-path :append true)
        autobuilder-atom     (atom nil)
        focus-ids-atom  (atom build-ids)
        build-once*     #(mapv (builder figwheel-server)
                               (config/narrow-builds* all-builds @focus-ids-atom))
        clean-build*    #(do
                           (mapv cbuild/clean-build (map :build-options all-builds))
                            (println "Deleting ClojureScript compilation target files."))
        run-autobuilder (fn [figwheel-server build-ids]
                           (binding [*out* log-writer
                                     *err* log-writer]
                             (reset! focus-ids-atom build-ids)
                             (build-once*)
                             (if-not @autobuilder-atom
                               (when-let [abuild (autobuild-ids
                                                  { :all-builds all-builds
                                                    :build-ids build-ids
                                                    :figwheel-server figwheel-server })]
                                 (reset! autobuilder-atom abuild)))))
        stop-autobuild*  #(if @autobuilder-atom
                            (do
                              (auto/stop-autobuild! @autobuilder-atom)
                              (reset! autobuilder-atom nil)
                              (println "Stopped Figwheel autobuild"))
                            (println "Autobuild not running."))
        start-autobuild* #(if-not @autobuilder-atom
                            (do
                              (run-autobuilder figwheel-server build-ids)
                              (println "Started Figwheel autobuilder see:" logfile-path ))
                            (println "Autobuilder already running."))
        reset-autobuild* #(do
                            (stop-autobuild*)
                            (clean-build*)
                            (start-autobuild*))]
    ;; add these functions to the browser-callbacks? YES
    { 'stop-autobuild  stop-autobuild*
      'start-autobuild start-autobuild*
      'reset-autobuild reset-autobuild*
      'build-once      build-once*
      'clean-build     clean-build*}))

(defn repl-function-docs  []
  "Figwheel Controls:
          (stop-autobuild)  ;; stops Figwheel autobuilder
          (start-autobuild) ;; starts Figwheel autobuilder
          (reset-autobuild) ;; stops, cleans, and starts autobuilder
          (build-once)      ;; builds source once time
          (clean-build)     ;; deletes compiled cljs target files
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3")

(defn autobuild-repl [{:keys [all-builds build-ids figwheel-server] :as opts}]
  (let [all-builds'  (mapv auto/prep-build all-builds)
        builds'      (config/narrow-builds* all-builds' build-ids) 
        control-fns  (setup-control-fns all-builds' build-ids figwheel-server)
        special-fns  (into {} (map (fn [[k v]] [k (wrap-special-no-args v)]) control-fns))]

    ((get control-fns 'start-autobuild))
    (newline)
    (if (:id (first builds'))
      (println "Launching ClojureScript REPL for build:" (:id (first builds')))
      (println "Launching ClojureScript REPL"))
    (println (repl-function-docs))
    (println "Prompt will show when figwheel connects to your application")
    (fig-repl/repl (first builds') figwheel-server {:special-fns special-fns})))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))

(defn run-autobuilder [{:keys [figwheel-options all-builds build-ids]}]
  (let [autobuild-options { :all-builds all-builds
                            :build-ids  build-ids
                            :figwheel-server (figwheel-sidecar.core/start-server
                                              figwheel-options)}]
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
