(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel.core :as fig]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]))

;; should for optimizations none here?

(defn get-dependency-mtimes [src-dirs build-options]
  (let [files (cbuild/files-that-can-change-build src-dirs build-options)]
    (into {}
          (map (juxt (fn [f] (.getCanonicalPath f))
               (fn [f] (.lastModified f)))
               (map :source-file files)))))

(def server-kill-switch (atom false))

(defn autobuild [src-dirs build-options figwheel-options]
  (let [figwheel-options' (merge figwheel-options (select-keys build-options [:ouput-dir :output-to]))
        figwheel-state' (fig/start-server figwheel-options')
        warning-handler (fn [warning-type env extra]
                          (when (warning-type cljs.analyzer/*cljs-warnings*)
                            (when-let [s (cljs.analyzer/error-message warning-type extra)]
                                (fig/compile-warning-occured figwheel-state' (cljs.analyzer/message env s)))))
        warning-handlers (conj cljs.analyzer/*cljs-warning-handlers* warning-handler)
        compiler-env    (cljs.env/default-compiler-env build-options)]
    (reset! server-kill-switch (:http-server figwheel-state'))

    #_(p/pprint src-dirs)
    #_(p/pprint build-options)
    #_(p/pprint figwheel-options)

    (loop [dependency-mtimes {}]
      (let [new-mtimes (get-dependency-mtimes src-dirs build-options)]
        (when (not= dependency-mtimes new-mtimes)
          (try
            ;; this is a good place to have a compile-started callback  
            (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
              (let [additional-changed-ns (cbuild/build-source-paths src-dirs build-options compiler-env)]
                (fig/check-for-changes figwheel-state' dependency-mtimes new-mtimes additional-changed-ns)))
            (catch Throwable e
              (clj-stacktrace.repl/pst+ e) ;; not sure if this is needed
              (fig/compile-error-occured figwheel-state' e))))
        (fig/check-for-css-changes figwheel-state')
        (Thread/sleep 100)
        (recur new-mtimes)))))
