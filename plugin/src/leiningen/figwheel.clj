(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [fs.core :as fs]
   [leiningen.cljsbuild.config :as config]
   [leiningen.cljsbuild.subproject :as subproject]
   [leiningen.core.eval :as leval]
   [clojure.java.io :as io]
   [clojure.string :as string]))

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

;; All I can say is I'm sorry about this but, it seems to be the best
;; way for me to reuse cljsbuild.

;; this is really deviating from cljsbuild at this point.
;; need to dig in and probably rewrite this

;; need to get rid of crossovers soon

(defn run-compiler [project {:keys [crossover-path crossovers builds]} live-reload-options]
  (println "Compiling ClojureScript.")
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (when (not-empty crossovers)
    (println "\033[31mWARNING: lein-cljsbuild crossovers are deprecated, and will be removed in future versions.\n
See https://github.com/emezeske/lein-cljsbuild/blob/master/doc/CROSSOVERS.md for details.\033[0m")
    (fs/mkdirs crossover-path))
  (let [parsed-builds (list (config/parse-notify-command (first builds)))]
    (run-local-project project crossover-path parsed-builds
     '(require 'cljsbuild.crossover 'cljsbuild.util 'clj-stacktrace.repl 'figwheel-sidecar.auto-builder)
     `(do
        (letfn [(copy-crossovers# []
                   (cljsbuild.crossover/copy-crossovers
                    ~crossover-path
                    '~crossovers))]
          (when (not-empty '~crossovers)
            (copy-crossovers#)
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (let [build# (first '~parsed-builds)]
            (figwheel-sidecar.auto-builder/autobuild (:source-paths build#)
                                             (:compiler build#)
                                             ~live-reload-options)))))))

(defn optimizations-none?
  "returns true if a build has :optimizations set to :none"
  [build]
  (= :none (get-in build [:compiler :optimizations])))

;; checking to see if output dir is in right directory
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn relativize-resource-paths
  "Relativize to the local root just in case we have an absolute path"
  [resource-paths]
  (mapv #(string/replace-first (norm-path %)
                               (str (norm-path (.getCanonicalPath (io/file ".")))
                                    "/") "") resource-paths))

(defn make-serve-from-display [{:keys [http-server-root resource-paths] :as opts}]
  (let [paths (relativize-resource-paths resource-paths)]
    (str "(" (string/join "|" paths) ")/" http-server-root)))

(defn output-dir-in-resources-root?
  "Check if the build output directory is in or below any of the configured resources directories."
  [{:keys [output-dir resource-paths http-server-root] :as opts}]
  (and output-dir
       (first (filter (fn [x] (.startsWith output-dir (str x "/" http-server-root)))
                      (relativize-resource-paths resource-paths)))))

(defn map-to-vec-builds
  "Cljsbuild allows a builds to be specified as maps. We acommodate that with this function
   to normalize the map back to the standard vector specification. The key is placed into the
   build under the :id key."
  [builds]
  (if (map? builds)
    (vec (map (fn [[k v]] (assoc v :id (name k))) builds))
    builds))

(defn narrow-to-one-build* 
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [ builds build-id]
  (let [builds (map-to-vec-builds builds)]
    (vector
     (if build-id
       (some #(and (= (:id %) build-id) %) builds)
       (first (filter optimizations-none? builds))))))

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-to-one-build
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [project build-id]
  (update-in project [:cljsbuild :builds] narrow-to-one-build* build-id))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [builds]} {:keys [http-server-root] :as opts}]
  (let [build (first builds)
        opts? (and (not (nil? build)) (optimizations-none? build))
        out-dir? (output-dir-in-resources-root? opts)]
    (if (nil? build)
      (list
       (str "Figwheel: "
            "No cljsbuild specified. You may have mistyped the build "
            "id on the command line or failed to specify a build in "
            "the :cljsbuild section of your project.clj. You need to have "
            "at least one build with :optimizations set to :none."))
      (map
       #(str "Figwheel Config Error (in project.clj) - " %)
       (filter identity
               (list
                (when-not opts?
                  "you have build :optimizations set to something other than :none")
                (when-not out-dir?
                  (str
                   (if (:output-dir build)
                     "your build :output-dir is not in a resources directory."
                     "you have not configured an :output-dir in your build")
                   (str "\nIt should match this pattern: " (make-serve-from-display opts))))))))))

(defn normalize-dir
  "If directory ends with '/' then truncate the trailing forward slash."
  [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir)) 
    (subs dir 0 (dec (count dir)))
    dir))

(defn normalize-output-dir [opts]
  (update-in opts [:output-dir] normalize-dir))

(defn apply-to-key
  "applies a function to a key, if key is defined."
  [f k opts]
  (if (k opts) (update-in opts [k] f) opts))

(defn prep-options
  "Normalize various configuration input."
  [opts]
  (->> opts
       normalize-output-dir
       (apply-to-key str :ring-handler)
       (apply-to-key vec :css-dirs)
       (apply-to-key vec :resource-paths)))

;; duh!! I should be forcing the optimizations :none option here.

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [project (narrow-to-one-build project (first build-ids))
        current-build (first (get-in project [:cljsbuild :builds]))
        figwheel-options (prep-options
                          (merge
                           { :output-dir (-> current-build :compiler :output-dir )
                             :output-to  (-> current-build :compiler :output-to ) }
                           (:figwheel project)
                           ;; we can get the resource paths from the project
                           (select-keys project [:resource-paths])))]
    (let [errors (check-for-valid-options
                  (:cljsbuild project)
                  figwheel-options)]
      (println (str "Figwheel: focusing on build-id " "'" (:id current-build) "'"))
      (if (empty? errors)
        (run-compiler project
                      (config/extract-options project)
                      figwheel-options)
        (mapv println errors)))))
