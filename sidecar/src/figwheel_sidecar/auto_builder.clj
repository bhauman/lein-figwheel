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

(defn builder [figwheel-state]
  (let [warning-handlers (conj cljs.analyzer/*cljs-warning-handlers*
                               (warning-handler figwheel-state))]
    
    (fn [{:keys [src-dirs build-options compiler-env old-mtimes new-mtimes] :as state}]
      (try
        (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
          (auto/compile-start state)
          (let [started-at (System/currentTimeMillis)
                additional-changed-ns (cbuild/build-source-paths src-dirs
                                                                 build-options
                                                                 compiler-env)]
            (auto/compile-success (assoc state :started-at started-at))
            (fig/check-for-changes figwheel-state
                                   old-mtimes
                                   new-mtimes
                                   additional-changed-ns)))
        (catch Throwable e
          (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
          (clj-stacktrace.repl/pst+ e)
          (fig/compile-error-occured figwheel-state e))))))

(defn autobuild [src-dirs build-options figwheel-options]
  (let [figwheel-options (merge figwheel-options
                                (select-keys build-options [:output-dir :output-to]))
        figwheel-state (fig/start-server figwheel-options)]
    (reset! server-kill-switch (:http-server figwheel-state))
    (auto/autobuild*
     {:src-dirs src-dirs
      :build-options build-options
      :builder (builder figwheel-state)
      :each-iteration-hook (fn [_] (fig/check-for-css-changes figwheel-state))})))
