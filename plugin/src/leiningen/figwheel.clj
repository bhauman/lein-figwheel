(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   #_[clojure.pprint :as pp]
   [leiningen.core.eval :as leval]
   [leiningen.clean :as clean]
   [clojure.java.io :as io]
   [clojure.set :refer [intersection]]
   [clj-fuzzy.metrics :as metrics]
   [simple-lein-profile-merge.core :as lm]))

(def _figwheel-version_ "0.5.4-SNAPSHOT")

(defn make-subproject [project paths-to-add]
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
                      paths-to-add)})
    (meta project)))

(defn eval-and-catch [project requires form]
  (leval/eval-in-project
   project
   `(try
      (do
        ~form
        (System/exit 0))
      (catch Exception e#
        (do
          (.printStackTrace e#)
          (System/exit 1))))
   requires))

;; well this is private in the leiningen.cljsbuild ns
(defn- run-local-project [project paths-to-add requires form]
  (let [project' (-> project
                   (update-in [:dependencies] conj ['figwheel-sidecar _figwheel-version_])
                   (update-in [:dependencies] conj ['figwheel _figwheel-version_])
                   (make-subproject paths-to-add))]
    (eval-and-catch project' requires form)))

(defn figwheel-exec-body [body]
  `(let [figwheel-sidecar-version#
         (when-let [version-var#
                    (resolve 'figwheel-sidecar.config/_figwheel-version_)]
           @version-var#)]
     (if (not= ~_figwheel-version_ figwheel-sidecar-version#)
       (println
        (str "Figwheel version mismatch!!\n"
             "You are using the lein-figwheel plugin with version: "
             (pr-str ~_figwheel-version_) "\n"
             "With a figwheel-sidecar library with version:        "
             (pr-str figwheel-sidecar-version#) "\n"
             "\n"
             "These versions need to be the same.\n"
             "\n"
             "Please look at your project.clj :dependencies to see what is causing this.\n"
             "You may need to run \"lein clean\" \n"
             "Running \"lein deps :tree\" can help you see your dependency tree."))
       ~body)))

;; get keys that are similar to the keys we need in the project
;; to allow figwheel validation to detect and report misspellings

(defn similar-key [ky ky2]
  (let [dist (metrics/levenshtein (name ky) (name ky2))]
    (when (<= dist 3)
      dist)))

(defn get-keylike [ky mp]
  (if-let [val (get mp ky)]
    [ky val]
    (when-let [res (not-empty
                    (sort-by
                     first
                     (keep (fn [[k v]]
                             (when-let [dist (and (map? v) (similar-key k ky))]
                               [dist [k v]])) mp)))]
      (-> res first second))))

(defn fuzzy-select-keys [m kys]
  (into {} (map #(get-keylike % m) kys)))

(defn fuzzy-select-keys-and-fix [m kys]
  (into {} (map #(let [[_ v] (get-keylike % m)] [% v]) kys)))


;; discover if the figwheel subprocess needs to wory about leiningen profile-merging
;; or if simple profile merging will do it

(defn simple-apply-lein-profiles [project]
  (let [{:keys [without-profiles included-profiles excluded-profiles]}
        (meta project)]
    (lm/apply-lein-profiles
     without-profiles
     (lm/subtract-profiles included-profiles
                           excluded-profiles))))

(defn profile-merging?
  ([project] (profile-merging? project identity))
  ([project f]
   (boolean
    (or
     (not= (f project)
           (f (:without-profiles (meta project))))
     (some (some-fn
            #(similar-key :figwheel %)
            #(similar-key :cljsbuild %))
           (lm/profile-top-level-keys project))))))

(defn simple-merge-works?
  ([project] (simple-merge-works? project identity))
  ([project f]
   (try
     (= (f project)
        (f (simple-apply-lein-profiles project)))
     (catch Throwable e
       false))))

(defn fuzzy-config-from-project [project]
  (fuzzy-select-keys project [:cljsbuild :figwheel]))


(comment
  
  (def r (leiningen.core.project/read))
  #_(meta (raw-project-with-profile-meta r))
  (not=   (fuzzy-config-from-project (:without-profiles (meta r)))
          (fuzzy-config-from-project r))

  (= (fuzzy-config-from-project r)
     (fuzzy-config-from-project
      (simple-apply-lein-profiles r))
     )
  
  (profile-merging? r fuzzy-config-from-project)
  (simple-merge-works? r fuzzy-config-from-project)

  (config-data-from-project (apply-simple-lein-merge r))
  (config-data-from-project r)
  
  
  (simple-apply-lein-profiles
   (with-meta {}
     {:without-profiles  {:figwheel {:once [2]
                                     :sets #{2}}
                          :profiles {:dev {:figwheel {:once [1]
                                                      :sets ^:replace #{1}}}}}
      :excluded-profiles []
      :included-profiles [:dev :user]}))
  
  )

(defn create-config-source [project config-source-data]
  (let [profile-merging (profile-merging? project fuzzy-config-from-project)]
    {:data config-source-data
     :file "project.clj"
     :profile-merging    profile-merging
     :simple-merge-works  (or (false? profile-merging)
                              (simple-merge-works? project fuzzy-config-from-project))
     :included-profiles (or (-> project meta :included-profiles) [])
     :excluded-profiles (or (-> project meta :excluded-profiles) [])}))

(defn run-figwheel [project config-source-data paths-to-add build-ids]
  (let [profile-merging (profile-merging? project)]
    (run-local-project
     project paths-to-add
     '(require 'figwheel-sidecar.repl-api)
     (figwheel-exec-body
      `(do
         (figwheel-sidecar.repl-api/system-asserts)
         (figwheel-sidecar.repl-api/launch-from-lein
          '~(create-config-source project config-source-data)
        '~(vec build-ids)))))))

(defn run-config-check [project config-source-data options]
  (let [profile-merging (profile-merging? project)]
    (run-local-project
     project
     []
     '(require 'figwheel-sidecar.repl-api)
     (figwheel-exec-body
      `(do
         (figwheel-sidecar.repl-api/system-asserts)
         (figwheel-sidecar.repl-api/validate-figwheel-conf
          '~(create-config-source project config-source-data)
          '~options))))))

(defn run-build-once [project config-source-data paths-to-add build-ids]
  (run-local-project
   project
   paths-to-add
   '(require 'figwheel-sidecar.repl-api)
   (figwheel-exec-body
    `(do
       (figwheel-sidecar.repl-api/system-asserts)
       (figwheel-sidecar.repl-api/build-once-from-lein
        '~(create-config-source project config-source-data)
        '~(vec build-ids))))))

;; clean the project if there has been a dependency change

(defn on-stamp-change [{:keys [file signature]} f]
  {:pre [(string? signature) (= (type file) java.io.File)]}
  (let [old-val (when (.exists file) (slurp file))]
    (when-not (= signature old-val) (f))
    (.mkdirs (.getParentFile (io/file (.getAbsolutePath file))))
    (spit file signature)))

(defn clean-on-dependency-change [{:keys [target-path dependencies] :as project}]
  (when (and target-path dependencies)
    (on-stamp-change
     {:file (io/file
             target-path
             "stale"
             "leiningen.figwheel.clean-on-dependency-change")
      :signature (pr-str (sort-by str dependencies))}
     #(do
        (println "Figwheel: Cleaning because dependencies changed")
        (clean/clean project)))))

;; configuration validation and management is internal to figwheel BUT ...



;; we need to be able to introspect the config because we need to add the
;; right cljs build source paths to the classpath
;; this is only needed because of :all-builds config option

;; this duplicates functionality in figwheel-sidcar.config but we
;; can't pull that code in soooo ...


(defn map-to-vec-builds
  [builds]
  (if (map? builds)
    (mapv (fn [[k v]] (assoc v :id (name k))) builds)
    builds))

(defn figwheel-edn-exists? []
  (.exists (io/file "figwheel.edn")))

(defn figwheel-edn []
  (and (figwheel-edn-exists?)
       (read-string (slurp (io/file "figwheel.edn")))))

(defn cljs-builds [data]
  (map-to-vec-builds
   (if-let [data (figwheel-edn)]
     (:builds data)
     (get-in (fuzzy-select-keys-and-fix data [:cljsbuild])
             [:cljsbuild :builds]))))

(defn figwheel-options [data]
  (if-let [data (figwheel-edn)]
    data
    (:figwheel (fuzzy-select-keys-and-fix data [:figwheel]))))

(defn normalize-data [data build-ids]
  {:figwheel-options (figwheel-options data)
   :all-builds (cljs-builds data)
   :build-ids build-ids})

(defn opt-none-build? [build]
  (let [optimizations (get-in build [:compiler :optimizations])]
    (or (nil? optimizations) (= optimizations :none))))

(defn named? [x]
  (when x
    (or
     (string? x)
     (instance? clojure.lang.Named x))))

(defn clean-id [id] (when (named? id) (name id)))

(def clean-ids (comp set (partial keep clean-id)))

(def clean-build-ids #(->> % (keep :id) clean-ids))

(def builds-to-start-build-ids (comp clean-ids :builds-to-start :figwheel-options))

(defn opt-none-build-ids [{:keys [all-builds]}]
  (->>
   (filter #(opt-none-build? %) all-builds)
   (keep :id)))

;; so this takes into account the priority of
;; - command line supplied build-ids
;; - :builds-to-start ids
;; and applies the effect of :load-all-builds
;; which narrows the classpath to only the builds
;; that are intended to be built
(defn intersect-not-empty [& args]
  (when (every? not-empty args)
    (not-empty (apply intersection (map set args)))))

(defn source-paths-for-classpath [{:keys [figwheel-options all-builds build-ids] :as data}]
  (let [all-build-ids (clean-build-ids all-builds)
        intersect     (partial intersect-not-empty all-build-ids)
        class-path-build-ids
        (if (false? (:load-all-builds figwheel-options))
          (or
           (intersect build-ids)
           (intersect (builds-to-start-build-ids data))
           (intersect (take 1 (opt-none-build-ids data)))
           all-build-ids)
          all-build-ids)
        classpath-builds (filter #(class-path-build-ids
                                   (and (named? (:id %))
                                        (name (:id %))))
                                 all-builds)]
    (vec
     (distinct
      (mapcat :source-paths classpath-builds)))))

(comment
  (def test-project
    {:cljsbuil
     {:builds
      {:prod {:source-paths ["src1" "src3"], :compiler {:optimizations :advanced}},
       :dev {:source-paths ["src1"], :compiler {:optimizations :none}},
       :example {:source-paths ["src2"], :compiler {}}}}  ,
     :figwhe {
              :builds-to-start ["asdf"]
              :load-all-builds false
              }})
  
  (source-paths-for-classpath (normalize-data test-project ["example"]))
  (figwheel-exec-body `())

  )

;; task
(defn check-config [project]
  (clean-on-dependency-change project)
  (run-config-check
   project
   (fuzzy-select-keys project [:cljsbuild :figwheel])
   {:no-start-option true}))

(defn build-once [project build-ids]
  (run-build-once
   project
   (fuzzy-select-keys project [:cljsbuild :figwheel])
   (source-paths-for-classpath
    (normalize-data project build-ids))
   (vec build-ids)))

(defn figwheel-main [project build-ids]
  (run-figwheel
   project
   (fuzzy-select-keys project [:cljsbuild :figwheel])
   (source-paths-for-classpath
    (normalize-data project build-ids))
   (vec build-ids)))

(defmulti fig-dispatch (fn [project args] (first args)))

(defmethod fig-dispatch :default [project build-ids]
  (figwheel-main project build-ids))

(defmethod fig-dispatch ":check-config" [project args]
  (check-config project))

(defmethod fig-dispatch ":once" [project build-ids]
  (build-once project (rest build-ids)))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (clean-on-dependency-change project)
  (fig-dispatch project build-ids))
