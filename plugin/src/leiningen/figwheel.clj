(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.core.eval :as leval]
   [leiningen.core.project :as lproj]
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
                            :mirrors
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

(defn run-compiler [project {:keys [data] :as figwheel-internal-config-data}]
  (run-local-project
   project (get data :all-builds)
   '(require 'figwheel-sidecar.repl-api)
   `(do
      (figwheel-sidecar.repl-api/system-asserts)
      (figwheel-sidecar.repl-api/start-figwheel-from-lein '~figwheel-internal-config-data))))

;; validation help

(defn read-project-with-profiles [project]
  (lproj/set-profiles (lproj/read)
                      (:included-profiles (meta project))
                      (:excluded-profiles (meta project))))

(defn config-sources [project]
  (if (fc/figwheel-edn-exists?)
    (repeatedly #(fc/->figwheel-config-source))
    (map fc/->lein-project-config-source
         (cons project
               (repeatedly #(read-project-with-profiles project))))))

(defn validate-figwheel-conf [project]
  (if-let [valid-config-data (->> (config-sources project)
                                  (map fc/->config-data)
                                  fc/color-validate-loop)]
    (do (println "\nFigwheel: Configuration Valid. Starting Figwheel ...")
        valid-config-data)
    (do (println "\nFigwheel: Configuration validation failed. Exiting ...")
        false)))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (fc/system-asserts)
  (when-let [config-data (validate-figwheel-conf project)]
    (let [{:keys [data] :as figwheel-internal-data}
          (-> config-data
              fc/config-data->figwheel-internal-config-data
              fc/prep-builds)
          {:keys [figwheel-options all-builds]} data
          ;; TODO this is really outdated
          errors (fc/check-config figwheel-options
                                  (fc/narrow-builds*
                                   all-builds
                                   build-ids))
          figwheel-internal-final (fc/populate-build-ids figwheel-internal-data build-ids)]
      #_(pp/pprint figwheel-internal-final)
      (if (empty? errors)
        (run-compiler project figwheel-internal-final)
        (mapv println errors)))))
