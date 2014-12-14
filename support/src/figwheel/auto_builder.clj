(ns figwheel.auto-builder
  (:require
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




(comment

  (def options {
                :output-to "resources/public/js/compiled/example.js"
                :output-dir "resources/public/js/compiled/out"
                  :optimizations :none
                  :warnings true
                })

  (def fig-opts {
                :output-to "resources/public/js/compiled/example.js"
                :output-dir "resources/public/js/compiled/out"

                 :resource-paths ["/Users/brucehauman/workspace/lein-figwheel/example/dev-resources"
                                  "/Users/brucehauman/workspace/lein-figwheel/example/resources"
                                  "/Users/brucehauman/workspace/lein-figwheel/example/other_resources"],
                 ;; these aren't needed anymore
                 :js-dirs ["resources/public/js/compiled/out" "resources/public/js/compiled/example.js"],
                 :name "figwheel-example",
                 :server-port 3449,

                 :open-file-command "emacsclient",
                 :root "/Users/brucehauman/workspace/lein-figwheel/example",
                 :version "0.1.7-SNAPSHOT",
                 :css-dirs ["resources/public/css"],
                 :http-server-root "public"})
  
  (get-dependency-mtimes ["src"] options)


  (autobuild ["src"] options fig-opts)
  
  )

(def server-kill-switch (atom false))

(defn autobuild [src-dirs build-options figwheel-options]
  (let [figwheel-state' (fig/start-server figwheel-options)
        warning-handler (fn [warning-type env extra]
                          (when (warning-type cljs.analyzer/*cljs-warnings*)
                            (when-let [s (cljs.analyzer/error-message warning-type extra)]
                                (fig/compile-warning-occured figwheel-state' (cljs.analyzer/message env s)))))
        warning-handlers (conj cljs.analyzer/*cljs-warning-handlers* warning-handler)
        compiler-env    (cljs.env/default-compiler-env build-options)]
    (reset! server-kill-switch (:http-server figwheel-state'))
    (loop [dependency-mtimes {}]
      (let [new-mtimes (get-dependency-mtimes ["src"] build-options)]
        (when (not= dependency-mtimes new-mtimes)
          (try
            ;; this is a good place to have a compile-started callback  
            (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
              (cbuild/build-source-paths src-dirs build-options compiler-env)
              (fig/check-for-changes figwheel-state' dependency-mtimes new-mtimes))
            (catch Throwable e
              (clj-stacktrace.repl/pst+ e) ;; not sure if this is needed
              (fig/compile-error-occured figwheel-state' e))))
        (fig/check-for-css-changes figwheel-state')
        (Thread/sleep 100)
        #_(fig/stop-server figwheel-state')
        (recur new-mtimes)))))

(comment
  (require 'figwheel.auto-builder)
  (in-ns 'figwheel.auto-builder)
  )
