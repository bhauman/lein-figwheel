(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.core.eval :as leval]
   [leiningen.core.project :as lproj]
   [clojure.java.io :as io]
   [figwheel-sidecar.config :as fc]
   #_[figwheel-sidecar.config-check.validate-config :as fvalidate]))

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

(defn run-compiler [project {:keys [all-builds build-ids] :as autobuild-opts}]
  (run-local-project
   project all-builds
   '(require 'figwheel-sidecar.repl-api)
   `(do
      (figwheel-sidecar.repl-api/system-asserts)
      (figwheel-sidecar.repl-api/start-figwheel-from-lein '~autobuild-opts))))

(defn figwheel-edn? [] (.exists (io/file "figwheel.edn")))

(defn config-data [project]
  (if (figwheel-edn?)
    (read-string (slurp "figwheel.edn"))
    project))

(defn should-validate-config? [config-data]
  (not
   (false? (if (figwheel-edn?)
             (get config-data :validate-config)
             (get-in config-data [:figwheel :validate-config])))))

(defn validate-figwheel-conf-helper [project]
  (when-let [validate-loop (resolve 'figwheel-sidecar.config-check.validate-config/color-validate-loop)]
    (if (figwheel-edn?)
      (validate-loop
        (repeatedly #(slurp "figwheel.edn"))
        {:file (io/file "figwheel.edn")
         :figwheel-options-only true})
      (validate-loop
        (cons project
              (repeatedly #(lproj/set-profiles (lproj/read)
                                               (:included-profiles (meta project))
                                               (:excluded-profiles (meta project)))))
        {:file (io/file "project.clj")}))))

(defn validate-figwheel-conf [project]
  (let [config-data (config-data project)]
    (if-not (should-validate-config? config-data)
      config-data
      (do
        (require 'figwheel-sidecar.config-check.validate-config)
        (if-let [config (validate-figwheel-conf-helper project)]
          (do (println "\nFigwheel: Configuration Valid. Starting Figwheel ...")
              config)
          (do (println "\nFigwheel: Configuration validation failed. Exiting ...")
              false))))))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (fc/system-asserts)
  (when-let [config-data (validate-figwheel-conf project)]
    (pp/pprint config-data)
    #_(let [{:keys [all-builds figwheel-options]}
          (-> config-data
              (fc/config build-ids)
              fc/prep-config)
          errors (fc/check-config figwheel-options
                                  (fc/narrow-builds*
                                   all-builds
                                   build-ids))]
      (if (empty? errors)
        (run-compiler project
                      { :figwheel-options figwheel-options
                        :all-builds all-builds
                        :build-ids  (vec build-ids)})
        (mapv println errors)))))
