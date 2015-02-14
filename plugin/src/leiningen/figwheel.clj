(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.cljsbuild.config :as config]
   [leiningen.cljsbuild.subproject :as subproject]
   [leiningen.core.eval :as leval]
   [clojure.java.io :as io]
   [figwheel-sidecar.config :as fc]))

(def figwheel-sidecar-version
  (let [[_ coords version]
        (-> (or (io/resource "META-INF/leiningen/figwheel-sidecar/figwheel-sidecar/project.clj")
                ; this should only ever come into play when testing figwheel-sidecar itself
                "project.clj")
            slurp
            read-string)]
    (assert (= coords 'figwheel-sidecar)
            (str "Something very wrong, could not find figwheel-sidecar's project.clj, actually found: "
                 coords))
    (assert (string? version)
            (str "Something went wrong, version of figwheel-sidecar is not a string: "
                 version))
    version))

;; well this is private in the leiningen.cljsbuild ns
(defn- run-local-project [project crossover-path builds requires form]
  (let [project' (-> project
                     (update-in [:dependencies] conj ['figwheel-sidecar figwheel-sidecar-version]) 
                     (subproject/make-subproject crossover-path builds)
                     #_(update-in [:dependencies] #(filter (fn [[n _]] (not= n 'cljsbuild)) %)))] 
    (leval/eval-in-project project'
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
     requires)))

(defn run-compiler [project crossover-path crossovers
                    {:keys [all-builds] :as autobuild-opts}]
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (when (not-empty crossovers)
    (println "\033[31mWARNING: lein-cljsbuild crossovers are deprecated, and will be removed in future versions.\n
See https://github.com/emezeske/lein-cljsbuild/blob/master/doc/CROSSOVERS.md for details.\033[0m")
    (.mkdirs (io/file crossover-path)))
  (run-local-project project crossover-path all-builds
     '(require 'cljsbuild.crossover 'cljsbuild.util 'figwheel-sidecar.repl)
     `(letfn [(copy-crossovers# []
                  (cljsbuild.crossover/copy-crossovers
                   ~crossover-path
                   '~crossovers))]
          (when (not-empty '~crossovers)
            (copy-crossovers#)
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (figwheel-sidecar.repl/run-autobuilder ~autobuild-opts))))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [{:keys [crossover-path crossovers builds]} (config/extract-options project)
        all-builds       (fc/prep-builds
                          (mapv config/parse-notify-command
                                (or (get-in project [:figwheel :builds])
                                    builds)))
        figwheel-options (fc/prep-options
                          (merge
                           { :http-server-root "public" }
                           (dissoc (:figwheel project) :builds)
                           (select-keys project [:resource-paths])))
        errors           (fc/check-config figwheel-options
                                          (fc/narrow-builds*
                                           all-builds
                                           build-ids))]
    (if (empty? errors)
      (run-compiler project crossover-path crossovers
                    { :figwheel-options figwheel-options
                      :all-builds all-builds
                      :build-ids  (vec build-ids)})
      (mapv println errors))))
