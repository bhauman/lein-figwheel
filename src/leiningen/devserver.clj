(ns leiningen.devserver
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
   [cljschangeserver.core]))

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

(defn run-compiler [project {:keys [crossover-path crossovers builds js-dirs]} build-ids watch?]
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
      '(require 'cljsbuild.compiler 'cljsbuild.crossover 'cljsbuild.util 'clojure.java.io 'cljschangeserver.core)
      `(do
        (letfn [(copy-crossovers# []
                  (cljsbuild.crossover/copy-crossovers
                    ~crossover-path
                    '~crossovers))]
          (copy-crossovers#)
          (when ~watch?
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (let [crossover-macro-paths# (cljsbuild.crossover/crossover-macro-paths '~crossovers)
                builds# (for [opts# '~parsed-builds]
                          [opts# (cljs.env/default-compiler-env (:compiler opts#))])]
            (let [change-server# (cljschangeserver.core/start-static-server {:js-dirs ~js-dirs})]
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
                            ~watch?))))]
                  (when ~watch?
                    (when (not= new-dependency-mtimes# dependency-mtimes# )
                      (println "Saving mtimes ...")
                      (cljschangeserver.core/check-for-changes change-server#)
                      #_(spit "./.cljsbuild-mtimes" "hello"))                  
                    (Thread/sleep 100)
                    (recur new-dependency-mtimes#))))
              )
            ))))))

(defn cljs-change-server-watch-dirs [project]
  (vec (map #(str "" %)
            (keep identity (mapcat (juxt :output-dir :output-to)
                                   (filter #(= :none (:optimizations %))
                                           (map :compiler (get-in project [:cljsbuild :builds]))))))))

(defn devserver
  "I don't do a lot."
  [project & args]
  (println "Running ClojureScript compiler!")
  #_(println (cljs-change-server-watch-dirs project))
  (let [options (assoc (config/extract-options project)
                  :js-dirs (cljs-change-server-watch-dirs project))
        build-ids args
        res (run-compiler project options build-ids true)]
    (println "finished running compiler" )))
