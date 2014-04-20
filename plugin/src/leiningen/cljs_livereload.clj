(ns leiningen.cljs-livereload
  (:refer-clojure :exclude [test])
  (:require
   [fs.core :as fs]
   [leiningen.clean :as lclean]
   [leiningen.cljsbuild.config :as config]
   [leiningen.cljsbuild.jar :as jar]
   [leiningen.cljsbuild.subproject :as subproject]
   [leiningen.compile :as lcompile]
   [leiningen.core.eval :as leval]
   [leiningen.core.main :as lmain]
   [leiningen.help :as lhelp]
   [leiningen.jar :as ljar]
   [leiningen.test :as ltest]
   [leiningen.trampoline :as ltrampoline]
   [robert.hooke :as hooke]
   [clojure.java.io :as io]
   [cljsbuild.compiler]
   [cljsbuild.crossover]
   [cljsbuild.util]
   [cljs-livereload.core]))

(defn- run-local-project [project crossover-path builds requires form]
  (leval/eval-in-project (subproject/make-subproject project crossover-path builds)
    ; Without an explicit exit, the in-project subprocess seems to just hang for
    ; around 30 seconds before exiting.  I don't fully understand why...
    `(try
       (do
         ~form
         (System/exit 0))
       (catch cljsbuild.test.TestsFailedException e#
         ; Do not print stack trace on test failure
         (System/exit 1))
       (catch Exception e#
         (do
           (.printStackTrace e#)
           (System/exit 1))))
    requires))

(defn run-compiler [project {:keys [crossover-path crossovers builds]} live-reload-options build-ids]
  (doseq [build-id build-ids]
    (if (empty? (filter #(= (:id %) build-id) builds))
      (throw (Exception. (str "Unknown build identifier: " build-id)))))
  (println "Compiling ClojureScript.")
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (when (not-empty crossovers)
    (println "\033[31mWARNING: lein-cljsbuild crossovers are deprecated, and will be removed in future versions. See https://github.com/emezeske/lein-cljsbuild/blob/master/doc/CROSSOVERS.md for details.\033[0m")
    (fs/mkdirs crossover-path))
  (let [filtered-builds (if (empty? build-ids)
                          builds
                          (filter #(some #{(:id %)} build-ids) builds))
        parsed-builds (map config/parse-notify-command filtered-builds)]
    (doseq [build parsed-builds]
      (config/warn-unsupported-warn-on-undeclared build)
      (config/warn-unsupported-notify-command build))
    (run-local-project project crossover-path parsed-builds
      '(require 'cljsbuild.compiler 'cljsbuild.crossover 'cljsbuild.util 'clojure.java.io 'cljs-livereload.core)
      `(do
        (letfn [(copy-crossovers# []
                  (cljsbuild.crossover/copy-crossovers
                    ~crossover-path
                    '~crossovers))]
          (when (not-empty '~crossovers)
            (copy-crossovers#)
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (let [crossover-macro-paths# (cljsbuild.crossover/crossover-macro-paths '~crossovers)
                builds# (for [opts# '~parsed-builds]
                          [opts# (cljs.env/default-compiler-env (:compiler opts#))])]
            (let [change-server# (cljs-livereload.core/start-static-server ~live-reload-options)]
              (loop [dependency-mtimes# (repeat (count builds#) {})]
                (let [builds-mtimes# (map vector builds# dependency-mtimes#)
                      new-dependency-mtimes#
                      (doall
                       (for [[[build# compiler-env#] mtimes#] builds-mtimes#]
                         (binding [cljs.env/*compiler* compiler-env#]
                           (cljsbuild.compiler/run-compiler
                            (:source-paths build#)
                            ~crossover-path
                            crossover-macro-paths#
                            (:compiler build#)
                            (:parsed-notify-command build#)
                            (:incremental build#)
                            (:assert build#)
                            mtimes#
                            true))))]
                  (when (not= new-dependency-mtimes# dependency-mtimes#)
                    (cljs-livereload.core/check-for-changes change-server# (first dependency-mtimes#) (first new-dependency-mtimes#)))
                  (Thread/sleep 100)
                  (recur new-dependency-mtimes#))))))))))

(defn cljs-change-server-watch-dirs [project]
  (vec
   ((juxt :output-dir :output-to)
    (:compiler (first (get-in project [:cljsbuild :builds]))))))

;; we are only going to work on one build to keep things simple
(defn narrow-to-one-build [project build-id-args]
  (update-in project [:cljsbuild :builds]
             (fn [builds]
                 (vector
                  (if-let [build (some #(and (= (:id %) (first build-id-args)) %)
                                       builds)]
                    build
                    (first builds))))))

(defn cljs-livereload
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [project (narrow-to-one-build project build-ids)
        live-reload-options (merge
                             {:js-dirs (cljs-change-server-watch-dirs project)
                              :output-dir (:output-dir (:compiler (first (get-in project [:cljsbuild :builds]))))}
                             (:cljs-livereload project))
        options (config/extract-options project)]
    (run-compiler project options live-reload-options build-ids)))

