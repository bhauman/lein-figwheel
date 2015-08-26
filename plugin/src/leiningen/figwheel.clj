(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.cljsbuild.config :as config]
   [leiningen.cljsbuild.subproject :as subproject]
   [leiningen.core.eval :as leval]
   [clojure.java.io :as io]
   [figwheel-sidecar.config :as fc]))

(defn get-lib-version [proj-name]
  (let [[_ coords version]
        (-> (io/resource (str "META-INF/leiningen/" proj-name "/" proj-name "/project.clj"))
            slurp
            read-string)]
    (assert (= coords (symbol proj-name))
            (str "Something very wrong, could not find " proj-name "'s project.clj, actually found: "
                 coords))
    (assert (string? version)
            (str "Something went wrong, version of " proj-name " is not a string: "
                 version))
    version))

(def figwheel-sidecar-version (get-lib-version "figwheel-sidecar"))

(def figwheel-version (get-lib-version "figwheel"))

;; well this is private in the leiningen.cljsbuild ns
(defn- run-local-project [project builds requires form]
  (let [project' (-> project
                   (update-in [:dependencies] conj ['figwheel-sidecar figwheel-sidecar-version])
                   (update-in [:dependencies] conj ['figwheel figwheel-version]) 
                   (subproject/make-subproject nil builds)
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

(defn run-compiler [project {:keys [all-builds] :as autobuild-opts}]
  (run-local-project project all-builds
     '(require 'cljsbuild.util 'figwheel-sidecar.repl)
     `(figwheel-sidecar.repl/run-autobuilder ~autobuild-opts)))

(defn get-unique-id [prj]
  (let [{:keys [name version]} prj]
    (when (and name version)
      (str name "--" version))))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [{:keys [builds]} (config/extract-options project)
        all-builds       (fc/prep-builds
                          (mapv config/parse-notify-command
                                (fc/map-to-vec-builds
                                 (or (get-in project [:figwheel :builds])
                                     (get-in project [:cljsbuild :builds])
                                     builds))))
        ;_ (pp/pprint all-builds)
        figwheel-options (fc/prep-options
                          (merge
                           { :http-server-root "public"
                             :unique-id (get-unique-id project)}
                           (dissoc (:figwheel project) :builds)
                           (select-keys project [:resource-paths])))
        errors           (fc/check-config figwheel-options
                                          (fc/narrow-builds*
                                           all-builds
                                           build-ids))]
    (if (empty? errors)
      (run-compiler project 
                    { :figwheel-options figwheel-options
                      :all-builds all-builds
                      :build-ids  (vec build-ids)})
      (mapv println errors))))
