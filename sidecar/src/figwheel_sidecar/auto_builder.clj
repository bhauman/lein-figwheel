(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as fig-repl]
   [figwheel-sidecar.config :as config]
   [cljs.repl]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.set :refer [intersection]]
   [clojure.stacktrace :as trace]
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

(defn setup-control-fns [all-builds build-ids figwheel-server]
  (let [logfile-path (or (:server-logfile figwheel-server) "figwheel_server.log")
        _ (mkdirs logfile-path)
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
        ;; we are going to take an optiona id here
        build-once*     (fn [ids]
                          (let [bs (set (get-ids ids))
                                builds (filter #(bs (:id %)) all-builds)]
                            (display-focus-ids (map :id builds))
                            (mapv auto/build-once builds)))
        clean-build*    (fn [ids]
                          (let [bs (set (get-ids ids))
                                builds (filter #(bs (:id %)) all-builds)]
                            (display-focus-ids (map :id builds))
                            (mapv cbuild/clean-build (map :build-options builds))
                            (println "Deleting ClojureScript compilation target files.")))
        run-autobuilder (fn [figwheel-server build-ids]
                          (when-not (builder-running?)
                            (build-once* build-ids)
                            (binding [*out* log-writer
                                      *err* log-writer]
                              (when-let [abuild (autobuild-ids
                                                 { :all-builds all-builds
                                                   :build-ids build-ids
                                                   :figwheel-server figwheel-server })]
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
                               (run-autobuilder figwheel-server build-ids')
                               (println "Started Figwheel autobuilder see:" logfile-path ))
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
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3")

(defn autobuild-repl [{:keys [all-builds build-ids figwheel-server] :as opts}]
  (let [all-builds'  (mapv auto/prep-build all-builds)
        repl-build   (first (config/narrow-builds* all-builds' build-ids))
        build-ids    (or (not-empty build-ids) [(:id repl-build)]) ;; give a default build-id
        control-fns  (setup-control-fns all-builds' build-ids figwheel-server)
        special-fns  (into {} (map (fn [[k v]] [k (make-special-fn v)]) control-fns))
        special-fns  (merge cljs.repl/default-special-fns special-fns)
        ;; TODO remove this when cljs.repl error catching code makes it to release
        special-fns  (into {} (map (fn [[k v]] [k (catch-special-fn-errors v)]) special-fns))]
    ((get control-fns 'start-autobuild) build-ids)
    (newline)
    (print "Launching ClojureScript REPL")
    (when-let [id (:id repl-build)] (println " for build:" id))
    (println (repl-function-docs))
    (println "Prompt will show when figwheel connects to your application")
    ;; it would be cool to switch the repl's build as well, is this possible?
    (fig-repl/repl repl-build figwheel-server {:special-fns special-fns})))

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
