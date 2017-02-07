(ns figwheel-sidecar.config
  (:require
   [clojure.pprint :as pp]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [clojure.set :refer [intersection]]
   [simple-lein-profile-merge.core :as lm]
   [figwheel-sidecar.utils.fuzzy :as fuz]
   [strictly-specking-standalone.ansi-util :refer [with-color-when color-text]]

   [figwheel-sidecar.utils :as utils]
   [strictly-specking-standalone.core :as speck]
   [figwheel-sidecar.schemas.config :as config-spec]
   [strictly-specking-standalone.spec :as s]
   [strictly-specking-standalone.strict-keys :as strictk]))

#_(remove-ns 'figwheel-sidecar.config)

(def _figwheel-version_ "0.5.10-SNAPSHOT")

;; file stamping pattern

(defn on-stamp-change [{:keys [file signature]} f]
  {:pre [(string? signature) (= (type file) java.io.File)]}
  (let [old-val (when (.exists file) (slurp file))]
    (when-not (= signature old-val) (f))
    (.mkdirs (.getParentFile (io/file (.getAbsolutePath file))))
    (spit file signature)))

(defmacro friendly-assert [v message]
  `(try
     (assert ~v ~message)
     true
     (catch Throwable e#
       (-> (.getMessage e#)
           (color-text :red)
           println))))

(defmacro system-exit-assert [v msg]
  `(when-not (friendly-assert ~v ~msg)
    (java.lang.System/exit 1)))

(defn system-asserts []
  (let [java-version (System/getProperty "java.version")]
    (friendly-assert (>= (compare java-version "1.8.0") 0)
                     (str "Java >= 1.8.0 - Figwheel requires Java 1.8.0 at least. Current version  "
                          java-version
                          "\n  Please install Java 1.8.0 at least.\n"
                          "  This may only be occuring in the Leiningen (bootstrapping) process but still something to be aware of.\n"
                          "  Especially if this message is immediately followed by an strange stack trace.\n" ))
    (when-not (>= (compare (clojure-version) "1.7.0") 0)
      (println
       (str
        "System Warning: Detected Clojure Version " (clojure-version) "\n"
        "  Figwheel requires Clojure 1.7.0 at least.\n"
        "  This may only be occuring in the Leiningen (bootstrapping) process but still something to be aware of.\n"
        "  Especially if this message is immediately followed by an strange stack trace.\n"
        "  Check lein deps :tree or lein deps :plugin-tree for clues.\n"
        "  Also, don't forget the influence of profiles.clj")))))

(defn get-build-options [build]
   (or (:build-options build) (:compiler build) {}))

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

;; TODO compiler probably handles this now
(defn ensure-output-dirs!
  "Given a build config ensures the existence of the output directories."
  [build]
  (let [{:keys [output-to]} (get-build-options build)]
    (when output-to
      (mkdirs output-to))
    build))

(defn opt-none?
  "Given a map of compiler options returns true if a build will be
  compiled in :optimizations :none mode"
  [{:keys [optimizations]}]
  (or (nil? optimizations) (= optimizations :none)))

(def optimizations-none? (comp opt-none? get-build-options))

(defn default-source-map-timestamp
  "If we are in a figwheel build,
  default :build-options :source-map-timestamp to true, unless it's
  explicitly set to false."
  [{:keys [figwheel] :as build}]
  (if figwheel
    (update-in build [:build-options :source-map-timestamp] #(if (false? %) % true))
    build))

(defn forward-devcard-option
  "Given a build-config has a [:figwheel :devcards] config it make
  sure that the :build-options has :devcards set to true"
  [{:keys [figwheel] :as build}]
  (if (and figwheel (:devcards figwheel))
    (assoc-in build [:build-options :devcards] true)
    build))

(defn forward-to-figwheel-build-id
  "Given a build config that has a :figwheel config in it "
  [{:keys [id figwheel] :as build}]
  (if (and figwheel id)
    (update-in build [:figwheel]
            (fn [x] (assoc (if (map? x) x {})
                          :build-id id)))
    build))

(defn figwheel-build? [build]
  (and (optimizations-none? build)
       (:figwheel build)))

(defn map-to-vec-builds
  "Cljsbuild allows a builds to be specified as maps. We acommodate that with this function
   to normalize the map back to the standard vector specification. The key is placed into the
   build under the :id key."
  [builds]
  (if (map? builds)
    (vec (map (fn [[k v]] (assoc v :id (name k))) builds))
    builds))

(defn narrow-builds*
  "Filters builds to the chosen build-ids or if no build-ids specified returns the first
   build with optimizations set to none."
  [builds build-ids]
  (let [builds (map-to-vec-builds builds)
        ;; ensure string ids
        builds (map #(update-in % [:id] name) builds)]
    (vec
     (keep identity
           (if-not (empty? build-ids)
             (keep (fn [bid] (first (filter #(= bid (:id %)) builds))) build-ids)
             [(first (filter optimizations-none? builds))])))))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [http-server-root] :as opts} print-warning build']
  (let [build-options (get-build-options build')
        opts? (and (not (nil? build-options))
                   (optimizations-none? build'))]
    (map
     #(str "Figwheel Config Error (in project.clj) build "
           (pr-str (:id build'))
           " - \n  " %)
     (filter identity
             (list
              (when-not opts?
                "the build :optimizations key is set to something other than :none")
              (when-not (:output-dir build-options)
                "the build does not have an :output-dir key"))))))

(defn check-config [figwheel-options builds & {:keys [print-warning]}]
  (if (empty? builds)
    (list
     (string/join "\n"
                  ["Figwheel Config Error : Failed to specify build to start"
                   "You may have mistyped the id of the build on the command line."
                   ""
                   "OR you may NOT have a default build configuration in the"
                   ":cljsbuild section of your project.clj."
                   ""
                   "Figwheel needs at least one build with :optimizations "
                   "set to :none or nil. "
                   ]))
    (mapcat (partial check-for-valid-options figwheel-options print-warning)
            builds)))

(defn normalize-dir
  "If directory ends with '/' then truncate the trailing forward slash."
  [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir))
    (subs dir 0 (dec (count dir)))
    dir))

(defn apply-to-key
  "applies a function to a key, if key is defined."
  [f k opts]
  (if (k opts) (update-in opts [k] f) opts))

;; TODO this is a hack! need to check all the places that I'm checking for
;; :optimizations :none and check for nil? or :none
(defn default-optimizations-to-none [build-options]
  (if (opt-none? build-options)
    (assoc build-options :optimizations :none)
    build-options))

(defn sane-output-to-dir [{:keys [output-to output-dir] :as options}]
  (letfn [(parent [fname] (if-let [p (.getParent (io/file fname))] (str p "/") ""))]
    (if (and #_(opt-none? options)
             (or (nil? output-dir) (nil? output-to)))
      (if (and (nil? output-dir) (nil? output-to))
        (assoc options :output-to "main.js" :output-dir "out")
        (if output-dir ;; probably shouldn't do this
          (assoc options :output-to (str (parent output-dir) "main.js"))
          (assoc options :output-dir (str (parent output-to) "out"))))
      options)))

(comment
  (default-optimizations-to-none {:optimizations :simple})

  (sane-output-to-dir {:output-dir "yes" })

  (sane-output-to-dir {:output-to "yes.js"})

  (sane-output-to-dir {:output-dir "yes/there"})

  (sane-output-to-dir {:output-to "outer/yes.js"})
  )

(defn fix-build-options [build-options]
  (->> build-options
       default-optimizations-to-none
       (apply-to-key normalize-dir :output-dir)
       sane-output-to-dir))

;; idempotent
(defn move-compiler-to-build-options [build]
  (-> build
      (assoc :build-options (get-build-options build))
      (dissoc :compiler)))

(defn propagate-source-paths-to-compile-and-watch-paths [{:keys [source-paths] :as build}]
  (cond-> build
    (not (contains? build :watch-paths))   (assoc :watch-paths source-paths)
    (not (contains? build :compile-paths)) (assoc :compile-paths source-paths)))

; idempotent
(defn ensure-id
  "Converts given build :id to a string and if no :id exists generate and id."
  [opts]
  (assoc opts
         :id (name (or
                    (:id opts)
                    (gensym "build_needs_id_")))))

(defn prep-build [build]
  (-> build
      ensure-id
      propagate-source-paths-to-compile-and-watch-paths
      move-compiler-to-build-options
      (update-in [:build-options] fix-build-options)
      forward-to-figwheel-build-id
      forward-devcard-option
      default-source-map-timestamp
      ensure-output-dirs!
      (vary-meta assoc ::prepped true)))

(defn prepped? [build]
  (-> build meta ::prepped))

(defn prep-build-if-not-prepped [build]
  (if-not (prepped? build)
    (prep-build build)
    build))

(defn prep-builds* [builds]
  (-> builds
      map-to-vec-builds
      (->> (mapv prep-build-if-not-prepped))))

(defn websocket-host->str [host]
  (cond
    (nil? host)               "localhost" ; default
    (string? host)            host
    (= host :js-client-host)  "[[client-hostname]]" ; will be set by figwheel.client/config-defaults
    (= host :server-hostname) "[[server-hostname]]"
    (= host :server-ip)       "[[server-ip]]"
    :else                     (throw (Exception. (str "Unrecognized :websocket-host " host)))))

(defn fill-websocket-url-template [server-port url]
  (cond-> url
    (.contains url "[[server-hostname]]")
    (string/replace "[[server-hostname]]" (.getHostName (java.net.InetAddress/getLocalHost)))

    (.contains url "[[server-ip]]")
    (string/replace "[[server-ip]]"       (.getHostAddress (java.net.InetAddress/getLocalHost)))

    (.contains url "[[server-port]]")
    (string/replace "[[server-port]]"     (str server-port))))

#_(fill-websocket-url-template 1234 "ws://[[server-ip]]:[[server-port]]/figwheel-ws")

(defn update-figwheel-connect-options [{:keys [server-port]} build]
  (if (figwheel-build? build)
    (let [host-str (websocket-host->str (get-in build [:figwheel :websocket-host]))]
      (-> build
          forward-to-figwheel-build-id
          (update-in [:figwheel] dissoc :websocket-host)
          (update-in [:figwheel :websocket-url]
                     #(or % (str "ws://" host-str ":" server-port "/figwheel-ws")))
          (update-in [:figwheel :websocket-url] (partial fill-websocket-url-template server-port))))
    build))

(comment

  (update-figwheel-connect-options {:server-port 5555}
                                   {:id 5
                                    :figwheel {:websocket-host "llllll"} :yeah 6})

  (update-figwheel-connect-options {:server-port 5555}
                                   {:figwheel {:websocket-host "llllll"
                                               :websocket-url "yep"}
                                    :yeah 6})

  (update-figwheel-connect-options {:server-port 5555}
                                   {:id "dev"
                                    :figwheel true
                                    :compiler {:optimizations :none}})

  (update-figwheel-connect-options {:server-port 5555}
                                   {:yeah 6
                                    :id "dev"
                                    :figwheel {:foo 1
                                               :websocket-host :js-client-host}})

  (update-figwheel-connect-options {:server-port 5555}
                                   {:yeah 6
                                    :id "dev"
                                    :figwheel {:foo 1
                                               :websocket-host :server-ip}})

  (update-figwheel-connect-options {:server-port 5555}
                                   {:yeah 6
                                    :id "dev"
                                    :figwheel {:foo 1
                                               :websocket-host :server-hostname}})

  )

(comment
  (fix-figwheel-symbol-keys {:on-jsload 'asdfasdf :hey 5})
  (prep-build-for-figwheel-client {})
  (prep-build-for-figwheel-client { :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5}})

  ((comp prep-build-for-figwheel-client forward-devcard-option)
   { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5}})
  ((comp prep-build-for-figwheel-client forward-devcard-option)
   { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5 :devcards true} :build-options {:fun false}})
 )

;; high level configuration helpers

(defn read-edn-file [file-name]
  (let [file (io/file file-name)]
    (when-let [body (slurp file)]
      (read-string body))))

(def get-project-config lm/read-raw-project)

(defn needs-to-merge-profiles? [project]
  (some (some-fn
         #(fuz/similar-key 0 :figwheel %)
         #(fuz/similar-key 0 :cljsbuild %))
        (lm/profile-top-level-keys project)))

#_(needs-to-merge-profiles? (lm/read-raw-project))

(defn merge-profiles? [project {:keys [simple-merge-works profile-merging]}]
  (let [needs-to-merge? (needs-to-merge-profiles? project)]
    (cond
      (and (some? profile-merging)
           (some? simple-merge-works))
      (and profile-merging simple-merge-works)
      (some? profile-merging)
      profile-merging
      (some? simple-merge-works)
      (and simple-merge-works needs-to-merge?)
      :else needs-to-merge?)))

(defn project-with-merged-profiles
  ([] (project-with-merged-profiles {}))
  ([{:keys [active-profiles] :as config-data}]
   (let [project (lm/read-raw-project)]
     (if (merge-profiles? project config-data)
       (do #_(println "::::::: Merging profiles !!!!!!")
           #_(prn "active-profiles" active-profiles)
           #_(prn (select-keys config-data [:profile-merging :simple-merge-works]))
           (lm/safe-apply-lein-profiles project
                                        (or (not-empty active-profiles)
                                            lm/default-profiles)))
       project))))

#_(project-with-merged-profiles #_{:profile-merging true :simple-merge-works true})

(defn figwheel-edn-exists? []
  (.exists (io/file "figwheel.edn")))

;; validation

;; TODO add :validate-interactive option
;; TODO add :validate-level option

;; TODO move to standalone

;; TODO figure out fuzzy

;; this expects the data to be narrowed to the relevant keys
(defn raise-one-error [spec {:keys [data file type] :as config-data}]
  (when-not (s/valid? spec data)
    (let [first-error (-> (s/explain-data spec data)
                          (speck/prepare-errors data file)
                          first)
          ;; TODO need to get ANSI right
          ;; with-color-when here
          message (binding [speck/*explain-header* "Figwheel Configuration Error"]
                    (-> first-error
                        speck/error->display-data
                        speck/explain-out*
                        with-out-str))]
      #_(println message)
      (throw
       (ex-info message
                {:reason :figwheel-configuration-validation-error
                 :validation-error first-error
                 :config-data config-data}))
      first-error)))

(defn lein-project-spec [project]
  ;; TODO this needs fuzzy key detection
  (let [proj-fixed (fuz/fuzzy-select-keys-and-fix project [:cljsbuild :figwheel])]
    (if (get-in proj-fixed [:figwheel :builds])
      ::config-spec/lein-project-with-figwheel-builds
      ::config-spec/lein-project-with-cljsbuild)))

(defn validate-project-config-data [{:keys [data] :as config-data}]
  (let [spec (lein-project-spec data)]
    (->>
     (fuz/fuzzy-select-keys data [:cljsbuild :figwheel])
     (assoc config-data :data)
     (raise-one-error spec))))

(defn validate-figwheel-edn-config-data [config-data]
  (raise-one-error ::config-spec/figwheel-edn config-data))

(defn validate-figwheel-config-data [config-data]
  (raise-one-error ::config-spec/figwheel-internal-config config-data))

(comment

  (def xxx (->config-data (->lein-project-config-source)))

  (lein-project-spec (:data xxx))

  (all-builds xxx)

  (validate-project-config-data xxx)
  (speck/explain (lein-project-spec (:data xxx))
                 (fuz/fuzzy-select-keys (:data xxx) [:cljsbuild :figwheel]))

  (let [x (fuz/fuzzy-select-keys (:data xxx) [:cljsbuild :figwheel])
        x (-> x (:cljsbuild x))]
    (validate-figwheel-edn-config-data {:data x})

    )

  (validate-figwheel-config-data {:data {:all-builds (all-builds xxx)}})
  )




;; configuration

(defprotocol ConfigData
  (figwheel-options [_])
  (all-builds [_])
  (build-ids [_])
  (-validate [_]))

(defrecord LeinProjectConfigData [data file]
  ConfigData
  (figwheel-options [_]
    (-> data :figwheel (dissoc :builds)))
  (all-builds [_]
    (map-to-vec-builds
     (or (get-in data [:figwheel :builds])
         (get-in data [:cljsbuild :builds]))))
  (build-ids [_]
    (get-in data [:figwheel :builds-to-start]))
  (-validate [self]
    (validate-project-config-data self)))

(defrecord FigwheelConfigData [data file]
  ConfigData
  (figwheel-options [_] (dissoc data :builds))
  (all-builds [_]       (map-to-vec-builds (:builds data)))
  (build-ids [_]        (:builds-to-start data))
  (-validate [self]
    (validate-figwheel-edn-config-data self)))

(defrecord FigwheelInternalConfigData [data file]
  ConfigData
  (figwheel-options [_] (:figwheel-options data))
  (all-builds [_]       (map-to-vec-builds (:all-builds data)))
  (build-ids [_]        (:build-ids data))
  (-validate [self]
    (validate-figwheel-config-data self)))

(defprotocol ConfigSource
  (-config-data [_]))

(defrecord LeinProjectConfigSource [data file]
  ConfigSource
  (-config-data [self]
    (map->LeinProjectConfigData
     (assoc self
            :data (or data (project-with-merged-profiles self))
            :file (or file "project.clj")))))

(defrecord FigwheelConfigSource [data file]
  ConfigSource
  (-config-data [self]
    (map->FigwheelConfigData
     (assoc self
            :data (or data (read-edn-file file))
            :file file))))

(defrecord FigwheelInternalConfigSource [data file]
  ConfigSource
  (-config-data [_] (->FigwheelInternalConfigData data file)))

(defn config-source? [x] (satisfies? ConfigSource x))

(defn config-data? [x]   (satisfies? ConfigData x))

(defn figwheel-internal-config-data? [x] (instance? FigwheelInternalConfigData x))

(defn ->config-data [config-source]
  (cond
    (config-data? config-source) config-source
    (config-source? config-source)
    (try
      (-config-data config-source)
      (catch Throwable e
        (println "Error reading Configuration")
        (-config-data (assoc config-source :data {} :read-exception e))))))

;; config source constructors
(defn ->figwheel-internal-config-source
  ([data] (->figwheel-internal-config-source data nil))
  ([data file] (->FigwheelInternalConfigSource data file)))

(defn ->figwheel-config-source
  ([] (->figwheel-config-source nil "figwheel.edn"))
  ([data] (->figwheel-config-source data nil))
  ([data file] (->FigwheelConfigSource data file)))

(comment
  (->config-data (->lein-project-config-source))
  (->config-data (->figwheel-config-source nil "/Users/bhauman/workspace/lein-figwheel/example/figwheel.edn"))

  )


(defn ->lein-project-config-source
  ([] (->lein-project-config-source nil))
  ([project-data]
   (->LeinProjectConfigSource project-data "project.clj")))

(defn detect-convert->config-source [data]
  (if (map? data)
    (let [internal-keys [:figwheel-options :all-builds]
          fig-edn-keys [:http-server-root
                             :server-port
                             :builds
                             :css-dirs
                             :ring-handler
                             :builds-to-start]
          internal-score
          (count (fuz/fuzzy-select-keys data internal-keys))
          fig-edn-score
          (count (fuz/fuzzy-select-keys data fig-edn-keys))]
      (cond
        (and (>= internal-score fig-edn-score)
             (> internal-score 0))
        (->figwheel-internal-config-source data)
        (and (>= fig-edn-score internal-score )
             (> fig-edn-score 0))
        (->figwheel-config-source data)
        :else
        (throw (ex-info
                (str "The configuration Map provided does not resemble a configuration.\n"
                     "It doesn't have any keys like: " (pr-str fig-edn-keys) "\n"
                     "Or keys like: " (pr-str internal-keys))
                    {:reason :not-a-configuration-map
                     :config-data data}))))
    (throw (ex-info "The configuration data provided is not a Map"
                    {:reason :configuration-not-a-map
                     :config-data data})) ))

(defn ->config-source [config-options]
  (if (or (config-source? config-options)
          (config-data? config-options))
    config-options
    (detect-convert->config-source config-options)))

(defn initial-config-source
  ([] (initial-config-source nil))
  ([project]
   (if (figwheel-edn-exists?)
     (->figwheel-config-source)
     (->lein-project-config-source project))))

;; ConfigData -> Boolean
(defn validate-config-data? [config-data]
  {:pre [(config-data? config-data)]}
  (not
   (or
    (false? (-> config-data meta :validate-config))
    (false? (-> config-data figwheel-options :validate-config)))))

;; ConfigData -> ValidationLevel
(defn validate-config-level [config-data]
  (let [opt (-> config-data figwheel-options :validate-config)]
    (condp = opt
      :warn-unknown-keys :warn
      :ignore-unknown-keys :ignore
      nil)))

(declare use-color?)

;; ConfigData -> ConfigData ; raises runtime exception with on configuration error
(defn validate-config-data [config-data]
  {:pre [(config-data? config-data)]
   :post [(config-data? %)]}
  (if (validate-config-data? config-data)
    (let [config-data (if (:data config-data) config-data (assoc config-data :data {}))]
      #_(println "VALIDATING!!!!")
      (with-color-when (use-color? config-data)
        (if-let [level (validate-config-level config-data)]
          (binding [strictk/*unknown-key-level* level]
            (-validate config-data))
          (-validate config-data)))
      config-data)
    config-data))

;; ConfigData -> ConfigData | nil
(defn print-validate-config-data [config-data]
  {:pre [(config-data? config-data)]
   :post [(or (config-data? %) (nil? %))]}
  (try
    (validate-config-data config-data)
    (catch Throwable e
      (if (-> e ex-data :reason (= :figwheel-configuration-validation-error))
        (do (println (.getMessage e))
            #_(ex-data e)
            nil)
        (throw e)))))

;; ConfigData -> FigwheelInternalData
(defn config-data->figwheel-internal-config-data [{:keys [file type data] :as config-data}]
  {:pre  [(config-data? config-data)]
   :post [(figwheel-internal-config-data? %)]}
  (if (figwheel-internal-config-data? config-data)
    config-data
    (->FigwheelInternalConfigData
     {:figwheel-options (figwheel-options config-data)
      :all-builds       (all-builds config-data)
      :build-ids        (build-ids config-data)}
     file)))

;; FigwheelConfigData -> FigwheelInternalData
(defn prep-builds [figwheel-internal-data]
  {:pre [(figwheel-internal-config-data? figwheel-internal-data)]
   :post [(figwheel-internal-config-data? figwheel-internal-data)]}
  (update-in figwheel-internal-data [:data :all-builds] prep-builds*))

;; this implements the load all builds functionality
;; where if :load-all-builds is false we limit the classpath and the
;; available builds
(defn limit-builds-to-build-ids [{:keys [figwheel-options all-builds build-ids] :as figwheel-internal}]
  (->> (if (-> figwheel-options :load-all-builds false?)
         (filter #((set build-ids) (:id %))
                 all-builds)
         all-builds)
      (assoc figwheel-internal :all-builds)))

;; FigwheelConfigData -> FigwheelConfigData
(defn populate-build-ids
  ([figwheel-internal-data]
   {:pre  [(figwheel-internal-config-data?  figwheel-internal-data)]
    :post [(figwheel-internal-config-data?  figwheel-internal-data)]}
   (update-in
    figwheel-internal-data
    [:data]
    (fn [{:keys [figwheel-options all-builds build-ids] :as figwheel-internal}]
      (-> (->> figwheel-options
               :builds-to-start
               (or (not-empty build-ids))
               (map name)
               not-empty
               (narrow-builds* all-builds)
               (mapv :id)
               (assoc figwheel-internal :build-ids))
          limit-builds-to-build-ids))))
  ([figwheel-internal-data build-ids]
   (populate-build-ids
    (assoc-in figwheel-internal-data [:data :build-ids] (not-empty (vec build-ids))))))

;; FigwheelInternalData -> FigwheelInternalData
(def prepped-figwheel-internal (comp populate-build-ids prep-builds))

;; ConfigData -> FigwheelInternalData
(def config-data->prepped-figwheel-internal
  (comp prepped-figwheel-internal config-data->figwheel-internal-config-data))

(defn config-source->prepped-figwheel-internal [config-source]
  (-> config-source
      ->config-data
      validate-config-data
      config-data->prepped-figwheel-internal
      (vary-meta assoc :validate-config false)))

(defn fetch-figwheel-config []
  (config-source->prepped-figwheel-internal (initial-config-source)))

(def fetch-config fetch-figwheel-config)

(defn get-project-builds []
  (-> (->lein-project-config-source)
      ->config-data
      all-builds))

;; detect and report bad build ids

(defn suggest-like [thing things]
  (->> things
       (map name)
       (map (juxt #(fuz/ky-distance (name thing) %)
                  identity))
       (filter first)
       (sort-by first)
       first
       second))

(defn suggestion [thing things]
  (when-let [suggest (suggest-like thing things)]
    (str "  Perhaps you meant " (pr-str suggest))))

#_(suggestion :hey [:heyy])

(defn report-if-bad-build-id [known-build-ids build-id]
  (when-not ((set (map name known-build-ids)) (name build-id))
    (throw (ex-info
            (str "Build Id Error: " (pr-str build-id)
                 "  is not a build-id in your configuration. \n"
                 "  Known build ids: " (pr-str (vec known-build-ids)) "\n"
                 (suggestion build-id known-build-ids) "\n")
            {:reason :bad-build-id :build-id build-id :known-build-ids known-build-ids}))
    true))

(defn report-if-bad-build-ids [known-build-ids build-ids]
  {:pre [(every? (some-fn string? symbol? keyword?) known-build-ids)
         (every? (some-fn string? symbol? keyword?) build-ids)]}
  (doseq [build-id build-ids]
    (report-if-bad-build-id known-build-ids build-id)))


#_(report-if-bad-build-ids #{:asdf :a} [:asf])



;; I'm doing a slight adjustment here to make up for the
;; :compiler / :build-options ambiguity
;; externally as far as start-figwheel! is concerned :compiler is
;; the appropriate option and :build-options is deprecated
;; internally :build-options will still continue to operate
;; I'm planning on cleaning this up in the near future
(defn adjust-to-internal-configuration-representation
  [figwheel-internal-config]
  {:pre [(figwheel-internal-config-data? figwheel-internal-config)]}
  (update-in figwheel-internal-config
             [:data :all-builds] (partial map move-compiler-to-build-options)))

#_(fetch-config)

;;; looping and waiting to fix config
;; probably needs to be in another namespace

(defn file-change-wait [file timeout]
  (let [orig-mod (.lastModified (io/file file))
        time-start (System/currentTimeMillis)]
    (loop []
      (let [last-mod (.lastModified (io/file (str file)))
            curent-time (System/currentTimeMillis)]
        (Thread/sleep 100)
        (when (and (= last-mod orig-mod)
                   (< (- curent-time time-start) timeout))
          (recur))))))

(defn get-choice [choices]
  (when-let [ch (read-line)]
    (let [ch (string/trim ch)]
      (if (empty? ch)
        (first choices)
        (if-not ((set (map string/lower-case choices)) (string/lower-case (str ch)))
          (do
            (print (str "Amazingly, you chose '" ch  "', which uh ... wasn't one of the choices.\n"
                        "Please choose one of the following ("(string/join ", " choices) "): "))
            (flush)
            (get-choice choices))
          ch)))))

(defn use-color? [config-data]
  (if-let [fig-opt (and (config-data? config-data) (figwheel-options config-data))]
    (if (false? (:ansi-color-output fig-opt)) false true)
    true))

(defn validate-loop-behavior [config-data]
  (if-let [fig-opt (and (config-data? config-data) (figwheel-options config-data))]
    (when (contains? fig-opt :validate-interactive)
      (let [vi (:validate-interactive fig-opt)]
        (cond
          (false? vi)   "q"
          (= :start vi) "s"
          (= :fix vi)   "f"
          (= :quit vi)  "q"
          :else nil)))))

;; well now we can use hawk so this should be rethought
(defn validate-loop
  ([lazy-config-data-list]
   (validate-loop lazy-config-data-list {}))
  ([lazy-config-data-list opts]
  (let [{:keys [file] :as first-config-data} (first lazy-config-data-list)]
    (if-not (validate-config-data? first-config-data)
      first-config-data
      (with-color-when (use-color? first-config-data)
        (if-not (and file (.exists (io/file file)))
          (do
            (println "Configuration file" (str file) "was not found")
            (System/exit 1))
          (let [file (io/file file)]
            (println "Figwheel: Validating the configuration found in" (str file))
            (loop [fix (or (:fix-loop opts) false)
                   lazy-config-data-list lazy-config-data-list]
              (when-let [config-data (first lazy-config-data-list)]
                (if (and (not (:read-exception config-data))
                         (utils/slow-output
                          (print-validate-config-data config-data)))
                  (do
                    (println (color-text "Figwheel: Configuration Valid :)" :green))
                    config-data)
                  (do
                    (when (:read-exception config-data)
                      (println (color-text "------------------ File Read Error ----------------" :cyan))
                      (println (str "Could not read your configuraton file - " (:file config-data)))
                      (println "--> " (color-text (.getMessage (:read-exception config-data)) :yellow ))
                      (println (color-text "---------------------------------------------------" :cyan)))
                    (println (color-text (str "Figwheel: There are errors in your configuration file - " (str file)) :red))
                    (let [choice (or (and (:once opts) "q")
                                     (and fix "f")
                                     (validate-loop-behavior first-config-data)
                                     (do
                                       (println "Figwheel: Would you like to ...")
                                       (println "(f)ix the error live while Figwheel watches for config changes?")
                                       (println "(q)uit and fix your configuration?")
                                       (if (:no-start-option opts)
                                         (print "Please choose f, or q and then hit Enter [f]: ")
                                         (do
                                           (println "(s)tart Figwheel anyway?")
                                           (print "Please choose f, q or s and then hit Enter [f]: ")))
                                       (flush)
                                       (get-choice ["f" "q" "s"])))]
                      (if (:return-first-command opts)
                        [:validate-loop-command ({"q" 0 "s" 10 "f" 11} choice)]
                        (condp = choice
                          nil false
                          "q" false
                          "s" config-data
                          "f" (if file
                                (do
                                  (println "Figwheel: Waiting for you to edit and save your" (str file) "file")
                                  (println (color-text "Hit Ctrl-C to quit ..." :cyan))
                                  (file-change-wait file (* 120 1000))
                                  (println (color-text (str "File change detected - validating ...") :magenta))
                                  (recur true (rest lazy-config-data-list)))
                                (do ;; this branch shouldn't be taken
                                  (Thread/sleep 1000)
                                  (recur true (rest lazy-config-data-list))))))
                      )))
                )))))))))

(defn validate-lein-project-loop [project-config-data options]
  #_(pp/pprint (select-keys project-config-data [:active-profiles
                                               :profile-merging :simple-merge-works]))
  (if (or
       (false? (:profile-merging project-config-data))
       (true?  (:simple-merge-works project-config-data)))
    (validate-loop
     (cons project-config-data
           (repeatedly #(->config-data (map->LeinProjectConfigSource
                                        (select-keys
                                         project-config-data
                                         [:active-profiles
                                          :profile-merging
                                          :simple-merge-works])))))
     options)
    (validate-loop [project-config-data] {:once true})))

#_(->config-data (map->LeinProjectConfigSource
                  (select-keys
                   {}
                   [:included-profiles :excluded-profiles
                    :profile-merging :simple-merge-works])))

(defn validate-loop-command? [x]
  (and (vector? x)
       (= :validate-loop-command (first x))))

(defn interactive-validate [config-data options]
  (condp = (type config-data)
    LeinProjectConfigData
    (validate-lein-project-loop config-data options)
    FigwheelConfigData
    (validate-loop
     (repeatedly #(->config-data
                   (->figwheel-config-source))))
    :else (validate-loop [config-data] {:once true})))
