(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.core.eval :as leval]
   [leiningen.core.project :as lproj]
   [leiningen.core.utils :as lutils]
   [leiningen.core.main :as lmain]
   [leiningen.clean :as clean]
   [clojure.java.io :as io]
   [figwheel-sidecar.config :as fc]
   [figwheel-sidecar.config-check.ansi :refer [with-color]]))

(def _figwheel-version_ "0.5.4-SNAPSHOT")

(defn figwheel-sidecar-version []
  (when-let [version-var (resolve 'figwheel-sidecar.config/_figwheel-version_)]
     @version-var))

(try
  (assert
   (= _figwheel-version_ (figwheel-sidecar-version))
   (str "Figwheel version mismatch!!\n"
        "You are using the lein-figwheel plugin with version: "
        (pr-str _figwheel-version_) "\n"
        "With a figwheel-sidecar library with version:        "
        (pr-str (figwheel-sidecar-version)) "\n"
        "\n"
        "These versions need to be the same.\n"
        "\n"
        "Please look at your project.clj :dependencies to see what is causing this.\n"
        "You may need to run \"lein clean\" \n"
        "Running \"lein deps :tree\" can help you see your dependency tree."))
  (catch Throwable e
    (lmain/abort (.getMessage e))))

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

;; well this is private in the leiningen.cljsbuild ns
(defn- run-local-project [project builds requires form]
  (let [project' (-> project
                   (update-in [:dependencies] conj ['figwheel-sidecar _figwheel-version_])
                   (update-in [:dependencies] conj ['figwheel _figwheel-version_])
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
      (figwheel-sidecar.repl-api/start-figwheel-from-lein '~(into {} figwheel-internal-config-data)))))

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
                                  fc/validate-loop)]
    (do (println "Figwheel: Configuration Valid. Starting Figwheel ...")
        valid-config-data)
    (do (println "Figwheel: Configuration validation failed. Exiting ...")
        false)))

(defn clean-on-dependency-change [{:keys [target-path dependencies] :as project}]
  (when (and target-path dependencies)
    (fc/on-stamp-change
     {:file (io/file
             target-path
             "stale"
             "leiningen.figwheel.clean-on-dependency-change")
      :signature (pr-str (sort-by str dependencies))}
     #(do
        (println "Figwheel: Cleaning because dependencies changed")
        (clean/clean project)))))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (fc/system-asserts)
  (clean-on-dependency-change project)
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
