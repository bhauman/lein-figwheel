(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]))

;; from cljsbuild
(def reset-color "\u001b[0m")
(def foreground-red "\u001b[31m")
(def foreground-green "\u001b[32m")

(defn- colorizer [c]
  (fn [& args]
    (str c (apply str args) reset-color)))

(def red (colorizer foreground-red))
(def green (colorizer foreground-green))

(defn- elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

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

    (loop [dependency-mtimes {}]
      (let [new-mtimes (get-dependency-mtimes src-dirs build-options)]
        (when (not= dependency-mtimes new-mtimes)
          (try
            ;; this is a good place to have a compile-started callback
            ;; can add *assert* binding here
            (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
              (println (str "Compiling \"" (:output-to build-options) "\" from " (pr-str src-dirs) "..."))
              (flush)
              (let [started-at (System/currentTimeMillis)
                    additional-changed-ns (cbuild/build-source-paths src-dirs build-options compiler-env)]
                (println (green (str "Successfully compiled \"" (:output-to build-options) "\" in " (elapsed started-at) ".")))
                (fig/check-for-changes figwheel-state' dependency-mtimes new-mtimes additional-changed-ns)))
            (catch Throwable e
              (println (red (str "Compiling \"" (:output-to build-options) "\" failed.")))
              (clj-stacktrace.repl/pst+ e) ;; not sure if this is needed
              (fig/compile-error-occured figwheel-state' e))))
        (fig/check-for-css-changes figwheel-state')
        (Thread/sleep 100)
        (recur new-mtimes)))))
