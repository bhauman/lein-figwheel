(ns figwheel-sidecar.config
  (:require
   [clojure.pprint :as p]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [figwheel-sidecar.config-check.validate-config :as vc]))

;; trying to keep this whole file clojure 1.5.1 compatible because
;; it is required by the leiningen process in the plugin
;; this should be a temporary situation

;; test this by loading the file into a 1.5.1 process

(defn friendly-assert [v message]
  (when-not v
    (do
      (print "System Assertion: ")
      (println message)
      ;; don't bail just in case there is some strange system problem
      #_(System/exit 1)
      )))

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
     #(str "Figwheel Config Error (in project.clj) - " %)
     (filter identity
             (list
              (when-not opts?
                "you have build :optimizations set to something other than :none")
              (when-not (:output-dir build-options)
                "you have not configured an :output-dir in your build"))))))

(defn check-config [figwheel-options builds & {:keys [print-warning]}]
  (if (empty? builds)
    (list
     (str "Figwheel: "
          "No cljsbuild specified. You may have mistyped the build "
          "id on the command line or failed to specify a build in "
          "the :cljsbuild section of your project.clj. You need to have "
          "at least one build with :optimizations set to :none."))
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
  (-> url
      (string/replace "[[server-hostname]]" (.getHostName (java.net.InetAddress/getLocalHost)))
      (string/replace "[[server-ip]]"       (.getHostAddress (java.net.InetAddress/getLocalHost)))
      (string/replace "[[server-port]]"     (str server-port))))

#_(fillin-websocket-url-template 1234 "ws://[[server-ip]]:[[server-port]]/figwheel-ws")

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
    (when-let [body (and (.exists file) (slurp file))]
      (edn/read-string body))))

(defn get-project-config
  "This loads the project map form project.clj without merging profiles."
  []
  (if (.exists (io/file "project.clj"))
    (->> (str "[" (slurp "project.clj") "]")
         read-string
         (filter #(= 'defproject (first %)))
         first
         (drop 3)
         (partition 2)
         (map vec)
         (into {}))
    {}))

(defn project-builds [project]
  (or (get-in project [:figwheel :builds])
      (get-in project [:cljsbuild :builds])))

(defn figwheel-edn-exists? []
  (.exists (io/file "figwheel.edn")))

(defn needs-lein-project-config? []
  (not (figwheel-edn-exists?)))

#_(defn retrieve-and-validate-config
  ([] (retrieve-and-validate-config nil)) 
  ([project-config-data]
   (let [project-based-config (needs-lein-project-config?)
         config-data (if project-based-config
                       (or project-config-data (get-project-config))
                       (read-edn-file "figwheel.edn"))
         error-message (with-out-str
                         (vc/validate-config-data config-data
                                                  (not project-based-config)))]
     (when (and error-message (not (string/blank? error-message)))
       (throw
        (ex-info error-message {:cause (str "Configuration error found in "
                                            (if project-based-config
                                              "project.clj"
                                              "figwheel.edn"))})))
     (vary-meta config-data
                assoc (if project-based-config ::lein-project ::figwheel-edn)
                true))))

#_(defn raw-config->config [config-data]
  (if (-> config-data meta ::figwheel-edn)
    {:figwheel-options (dissoc config-data :builds)
     :all-builds (:builds config-data)}
    {:figwheel-options (dissoc (:figwheel config-data) :builds)
     :all-builds (project-builds config-data)}))



;; ConfigSource methods

(defn read-config-source-data [{:keys [data read-fn type] :as config-source}]
  (cond
    data data
    read-fn (read-fn)
    :else (condp = type
            ::lein-project (get-project-config)
            ::figwheel-edn (slurp "figwheel.edn"))))

(defn read-config-source [{:keys [data read-fn] :as config-source}]
  (assoc
   config-source
   :data (read-config-source-data config-source)))

;; ConfigData methods



;; ConfigData -> nil ; raises runtime exception with on configuration error
(defn validate-config-data [{:keys [file type data] :as config-data}]
  (condp = type
    ::figwheel-edn    (vc/validate-figwheel-edn-config-data config-data)
    ::lein-project    (vc/validate-project-config-data      config-data)
    ::figwheel-config (vc/validate-figwheel-config-data     config-data)))

;; ConfigData -> FigwheelConfig
(defn canonical-figwheel-config [{:keys [file type data] :as config-data}]
  (condp = type
    ::figwheel-edn {:figwheel-options (dissoc data :builds)
                    :all-builds (:builds data)}
    ::lein-project {:figwheel-options (dissoc (:figwheel data) :builds)
                    :all-builds (project-builds data)}))

;; FigwheelConfig, ConfigData -> nil or runtime exception with config error
(defn validate-figwheel-config [figwheel-config config-data]
  (validate-config-data (assoc
                         config-data
                         :type ::figwheel-config
                         :data figwheel-config)))

;; FigwheelConfig -> FigwheelConfig
(defn prep-builds [figwheel-config]
  (update-in figwheel-config [:all-builds] prep-builds*))


;; FigwheelConfig -> FigwheelConfig
(defn populate-build-ids [{:keys [all-builds build-ids figwheel-options]
                           :as figwheel-config}]
  (->> figwheel-options
       :builds-to-start
       (map name)
       (or build-ids)
       not-empty
       (narrow-builds* all-builds)
       (mapv :id)
       (assoc figwheel-config :build-ids))
  #_(assoc figwheel-config
           :build-ids
           (mapv :id
                 (narrow-builds* all-builds
                                 (not-empty
                                (or build-ids
                                    (map name
                                         (:builds-to-start figwheel-options))))))))

;; FigwheelConfig -> FigwheelConfig
(def prepped-figwheel-config (comp populate-build-ids prep-builds*))

(defn intial-config-source []
  (if (figwheel-edn-exists?)
    {:file "figwheel.edn" :type ::figwheel-edn}
    {:file "project.clj"  :type ::lein-project}))

(defn fetch-figwheel-config []
  (let [config-data (read-config-source (intial-config-source))]
    (validate-config-data config-data)
    (-> config-data
        canonical-figwheel-config
        prepped-figwheel-config)))

(def fetch-config fetch-figwheel-config)

#_(fetch-config)

(comment
  ConfigSource [:file :type [optional :data] [optional :read-fn]] ;; optional data is an identity under read config source
  read-config-source (ConfigSource) -> ConfigData [:data :type :file]
  validate-config-data (ConfigData) -> nil ;; raises exception
  canonical-figwheel-config (ConfigData) -> FigwheelConfig [:figwheel-options :all-builds :build-ids]
  validate-figwheel-config (FigwheelConfig, ConfigData) -> nil ;; raises exception
  prep-builds (FigwheelConfig) -> FigwheelConfig
  populate-build-ids (FigwheelConfig) -> FigwheelConfig
  ;; this is a convenience method that does both of the above
  prepped-figwheel-config (FigwheelConfig) -> FigwheelConfig
  
  )


#_(raw-config->config (retrieve-and-validate-config))

#_(defn config
  ([] (config (get-project-config) nil))
  ([project] (config project nil))
  ([project build-ids]
   (assoc
    (raw-config->config (if (needs-lein-project-config?)
                          project
                          (read-edn-file "figwheel.edn")))
    (if-not (needs-lein-project-config?)
      (let [fig-opts (read-edn-file "figwheel.edn")]
        {:figwheel-options (dissoc fig-opts :builds)
         :all-builds (:builds fig-opts)})
      {:figwheel-options (dissoc (:figwheel project) :builds)
       :all-builds (project-builds project)})
    :build-ids build-ids)))

#_(defn prep-config [config]
  (let [prepped (update-in config [:all-builds] prep-builds)]
    (assoc prepped
           :build-ids
           (mapv :id
                 (narrow-builds* (:all-builds prepped)
                                 (not-empty
                                  (or (:build-ids prepped)
                                      (map name (get-in prepped [:figwheel-options :builds-to-start])))))))))

#_(defn fetch-config []
  (prep-config (config)))
