(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.core.eval :as leval]
   [clojure.java.io :as io]
   [figwheel-sidecar.config :as fc]))

(defn make-subproject [project builds]
  (with-meta
    (merge
      (select-keys project [:checkout-deps-shares
                            :eval-in
                            :jvm-opts
                            :local-repo
                            :dependencies
                            :repositories
                            :resource-paths])
      {:local-repo-classpath true
       :source-paths (concat
                       (:source-paths project)
                       (mapcat :source-paths builds))})
    (meta project)))

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
                   (make-subproject builds))] 
    (leval/eval-in-project project'
     `(try
        (do
          ~form
          (System/exit 0))
        (catch Exception e#
          (do
            (.printStackTrace e#)
           (System/exit 1))))
     requires)))

(defn get-unique-id [prj]
  (let [{:keys [name version]} prj]
    (when (and name version)
      (str name "--" version))))

(defn run-compiler [project {:keys [all-builds
                                    build-ids
                                    needs-project-config] :as autobuild-opts}]
  (run-local-project project all-builds
     '(require 'figwheel-sidecar.system)
     (if needs-project-config
       `(figwheel-sidecar.system/run-autobuilder ~autobuild-opts)
       `(figwheel-sidecar.system/load-config-run-autobuilder {:build-ids ~build-ids}))))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [needs-project-config? (fc/needs-lein-project-config? project)
        {:keys [all-builds figwheel-options]}
        (-> project
            fc/figwheel-ambient-config
            fc/prep-figwheel-config)
        figwheel-options (merge
                          {:http-server-root "public"
                           :unique-id (get-unique-id project)}
                          figwheel-options)
        errors           (fc/check-config figwheel-options
                                          (fc/narrow-builds*
                                           all-builds
                                           build-ids))]
    (if (empty? errors)
      (run-compiler project 
                    { :needs-project-config needs-project-config?
                      :figwheel-options figwheel-options
                      :all-builds all-builds
                      :build-ids  (vec build-ids)})
      (mapv println errors))))
