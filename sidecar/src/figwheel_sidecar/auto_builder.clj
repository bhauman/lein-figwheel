(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]))

(def server-kill-switch (atom false))

(defn warning-handler [figwheel-state]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (fig/compile-warning-occured figwheel-state (cljs.analyzer/message env s))))))

(defn builder [figwheel-state']
  (let [warning-handlers (conj cljs.analyzer/*cljs-warning-handlers*
                               (warning-handler figwheel-state'))]
    (fn [{:keys [build-options old-mtimes new-mtimes id] :as build}]
      ;; this is where we are having to handle the seperate builds
      ;; better to pass the build into figwheel eh?
      (let [figwheel-state 
            (merge figwheel-state'
                   (if id {:build-id id} {})
                   (select-keys build-options [:output-dir :output-to]))]
        (try
          (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
            (auto/compile-start build)
            (let [started-at (System/currentTimeMillis)
                  additional-changed-ns
                  (:additional-changed-ns (cbuild/build-source-paths* build))]
              (auto/compile-success (assoc build :started-at started-at))
              (fig/check-for-changes figwheel-state
                                     old-mtimes
                                     new-mtimes
                                     additional-changed-ns)))
          (catch Throwable e
            (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
            (clj-stacktrace.repl/pst+ e)
            (fig/compile-error-occured figwheel-state e)))))))

(defn autobuild* [builds figwheel-options]
  (let [figwheel-state (fig/start-server figwheel-options)]
    (reset! server-kill-switch (:http-server figwheel-state))
    (auto/autobuild-blocking*
     {:builds  builds
      :builder (builder figwheel-state)
      :each-iteration-hook (fn [_] (fig/check-for-css-changes figwheel-state))})))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* [{:source-paths src-dirs
                :build-options build-options}]
              figwheel-options))
