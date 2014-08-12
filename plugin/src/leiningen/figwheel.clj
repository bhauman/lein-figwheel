(ns leiningen.figwheel
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
   [cljsbuild.util :as util]
   [figwheel.core :refer [resource-paths-pattern-str]]))

;; well this is private in the leiningen.cljsbuild ns
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

;; All I can say is I'm sorry about this but, it seems to be the best
;; way for me to reuse cljsbuild.
(defn run-compiler [project {:keys [crossover-path crossovers builds]} live-reload-options]
  (println "Compiling ClojureScript.")
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (when (not-empty crossovers)
    (println "\033[31mWARNING: lein-cljsbuild crossovers are deprecated, and will be removed in future versions. See https://github.com/emezeske/lein-cljsbuild/blob/master/doc/CROSSOVERS.md for details.\033[0m")
    (fs/mkdirs crossover-path))
  (let [parsed-builds (list (config/parse-notify-command (first builds)))]
    (config/warn-unsupported-warn-on-undeclared (first parsed-builds))
    (config/warn-unsupported-notify-command (first parsed-builds))
    (run-local-project project crossover-path parsed-builds
     '(require 'cljsbuild.compiler 'cljsbuild.crossover 'cljsbuild.util 'clj-stacktrace.repl 'clojure.java.io 'figwheel.core)
     `(do
        (letfn [(copy-crossovers# []
                   (cljsbuild.crossover/copy-crossovers
                    ~crossover-path
                    '~crossovers))]
          (when (not-empty '~crossovers)
            (copy-crossovers#)
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (let [crossover-macro-paths# (cljsbuild.crossover/crossover-macro-paths '~crossovers)
                build# (first '~parsed-builds)
                cljs-paths# (:source-paths build#)
                compiler-options# (:compiler build#)                
                compiler-env# (cljs.env/default-compiler-env compiler-options#) 
                change-server# (figwheel.core/start-static-server ~live-reload-options)]
            ;; added the following functions to help with error reporting.
            (letfn [(get-mtimes# [paths#]
                      (into {}
                            (map (fn [path#] [path# (.lastModified (io/file path#))]) paths#)))
                    (get-dependency-mtimes# []
                      (let [macro-files# (map :absolute crossover-macro-paths#)
                            clj-files-in-cljs-paths#
                            (into {}
                                  (for [cljs-path# cljs-paths#]
                                    [cljs-path# (cljsbuild.util/find-files cljs-path# #{"clj"})]))
                            cljs-files# (mapcat #(cljsbuild.util/find-files % #{"cljs"})
                                                (if ~crossover-path
                                                  (conj cljs-paths# ~crossover-path)
                                                  cljs-paths#))
                            lib-paths# (:libs compiler-options#)
                            js-files# (->> (or lib-paths# [])
                                           (mapcat #(cljsbuild.util/find-files % #{"js"}))
                                          ; Don't include js files in output-dir or our output file itself,
                                          ; both possible if :libs is set to [""] (a cljs compiler workaround to
                                          ; load all libraries without enumerating them, see
                                          ; http://dev.clojure.org/jira/browse/CLJS-526)
                                           (remove #(.startsWith ^String % (:output-dir compiler-options#)))
                                           (remove #(.endsWith ^String % (:output-to compiler-options#))))
                            macro-mtimes# (get-mtimes# macro-files#)
                            clj-mtimes# (get-mtimes# (mapcat second clj-files-in-cljs-paths#))
                            cljs-mtimes# (get-mtimes# cljs-files#)
                            js-mtimes# (get-mtimes# js-files#)]
                        (merge macro-mtimes# clj-mtimes# cljs-mtimes# js-mtimes#)))]
              (loop [dependency-mtimes# {}]
                (let [new-dependency-mtimes#
                      (try
                        (let [new-mtimes# (binding [cljs.env/*compiler* compiler-env#]
                                            (cljsbuild.compiler/run-compiler
                                             (:source-paths build#)
                                             ~crossover-path
                                             crossover-macro-paths#
                                             (:compiler build#)
                                             (:parsed-notify-command build#)
                                             (:incremental build#)
                                             (:assert build#)
                                             dependency-mtimes#
                                             false))]
                          (when (not= dependency-mtimes# new-mtimes#)
                            (figwheel.core/check-for-changes change-server# dependency-mtimes# new-mtimes#))
                          new-mtimes#)
                        (catch Throwable e#
                          (clj-stacktrace.repl/pst+ e#)
                          ;; this is a total crap hack
                          ;; just trying to delay duplicating a 
                          ;; portion of cljsbuild until I understand
                          ;; more about how lein figwheel should work
                          (figwheel.core/compile-error-occured change-server# e#)
                          (get-dependency-mtimes#)))]
                  (figwheel.core/check-for-css-changes change-server#)
                  (Thread/sleep 100)
                  (recur new-dependency-mtimes#))))))))))

(defn cljs-change-server-watch-dirs [project]
  (vec
   ((juxt :output-dir :output-to)
    (:compiler (first (get-in project [:cljsbuild :builds]))))))

(defn optimizations-none? [build]
  (= :none (get-in build [:compiler :optimizations])))

(defn resources-pattern-str [opts]
  (resource-paths-pattern-str (update-in opts [:http-server-root] (fn [x] (or x "public")))))

(defn output-dir-in-resources-root? [{:keys [output-dir] :as opts}]
  (re-matches (re-pattern (str (resources-pattern-str opts) ".*")) output-dir))

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-to-one-build [project build-id]
  (update-in project [:cljsbuild :builds]
             (fn [builds]
               (let [opt-none-builds (filter optimizations-none?
                                             builds)]
                 (vector
                  (if-let [build (some #(and (= (:id %)
                                                build-id) %)
                                       opt-none-builds)]
                    build
                    (first opt-none-builds)))))))

(defn check-for-valid-options [{:keys [builds]} {:keys [http-server-root] :as opts}]
  (let [build (first builds)
        opts? (and (not (nil? build)) (optimizations-none? build))
        out-dir? (output-dir-in-resources-root? opts)]
    (when (nil? build)
      (throw (Exception.
              (str "No cljsbuild specified. You could have mistyped the build
                    id or failed to specify build in your cljsbuild configuration in you project.clj"))))
    (when-not  opts?
      (println "Figwheel Config Error - you have build :optimizations set to something other than :none"))
    (when-not out-dir?
      (println (str "Figwheel Config Error - your build :output-dir is not in a resources directory."))
      (println (str "It should match this pattern: " (resources-pattern-str opts))))
    (and opts? out-dir?)))

(defn normalize-dir [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir)) 
    (subs dir 0 (dec (count dir)))
    dir))

(defn normalize-build [build]
  (-> build
      (update-in [:compiler :output-dir] normalize-dir)))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [build-id (first build-ids)
        project (narrow-to-one-build project build-id)
        current-build (normalize-build (first (get-in project [:cljsbuild :builds])))
        options (config/extract-options project)
        ;; live-reload-options has to be serializable and readable
        ;; because it is serialized in the macro above
        live-reload-options (merge
                             { :root (:root project)
                               :resource-paths (vec (:resource-paths project)) ;; make this a vector for the reader
                               :js-dirs (cljs-change-server-watch-dirs project)
                               :output-dir (:output-dir (:compiler current-build))
                               :output-to (:output-to (:compiler current-build))}
                             (:figwheel project))]
    (when (check-for-valid-options (:cljsbuild project) live-reload-options)
      (run-compiler project options live-reload-options))))

