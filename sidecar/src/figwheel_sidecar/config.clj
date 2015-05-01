(ns figwheel-sidecar.config
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.walk :as walk]))

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

(defn get-build-options
  ([build]
   (or (:build-options build) (:compiler build) {}))
  ([build key]
   (get (get-build-options build) key)))

(defn ensure-output-dirs* [{:keys [build-options compiler]}]
  (let [{:keys [output-to]} (or build-options compiler)]
    (when output-to
      (mkdirs output-to))))

(defn optimizations-none?
  "returns true if a build has :optimizations set to :none"
  [build]
  (let [opt (get-build-options build :optimizations)]
    (or (nil? opt) (= :none opt))))

;; checking to see if output dir is in right directory
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn relativize-resource-paths
  "Relativize to the local root just in case we have an absolute path"
  [resource-paths]
  (mapv #(string/replace-first (norm-path (.getCanonicalPath (io/file %)))
                               (str (norm-path (.getCanonicalPath (io/file ".")))
                                    "/") "") resource-paths))

(defn make-serve-from-display [{:keys [http-server-root resource-paths] :as opts}]
  (let [paths (relativize-resource-paths resource-paths)]
    (str "(" (string/join "|" paths) ")/" http-server-root)))

(defn output-dir-in-resources-root?
  "Check if the build output directory is in or below any of the configured resources directories."
  [{:keys [output-dir] :as build-options}
   {:keys [resource-paths http-server-root] :as opts}]
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

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-builds
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [project build-ids]
  (update-in project [:cljsbuild :builds] narrow-builds* build-ids))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [http-server-root] :as opts} print-warning build']
  (let [build-options (get-build-options build')
        opts? (and (not (nil? build-options)) (optimizations-none? build'))]
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

(defn namify-module-entries [module-map]
  (if (map? module-map)
    (into {} (map (fn [[k v]]
                    (if (and (map? v)
                             (get v :entries))
                      [k (update-in v [:entries] #(set (map name %)))]
                      [k v]))
                  module-map))
    module-map))

(defn opt-none? [{:keys [optimizations]}]
  (or (nil? optimizations) (= optimizations :none)))

;; TODO this is a hack need to check all the places that I'm checking for
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
    (default-optimizations-to-none)
    (apply-to-key normalize-dir :output-dir)
    (sane-output-to-dir)
    (apply-to-key namify-module-entries :modules)
    (apply-to-key name :main)))

(defn ensure-id [opts]
  (if (nil? (:id opts))
    (assoc opts :id (name (gensym "build_needs_id_")))
    (assoc opts :id (name (:id opts)))))

(defn move-compiler-to-build-options [build]
  (if (and (not (:build-options build))
           (:compiler build))
    (-> build
      (assoc :build-options (:compiler build))
      (dissoc :compiler))
    build))

(defn fix-build [build]
  (-> build
    ensure-id
    move-compiler-to-build-options
    (update-in [:build-options] fix-build-options)))

(defn fix-builds [builds]
  (mapv fix-build builds))

(defn no-seqs [b]
  (walk/postwalk #(if (seq? %) (vec %) %) b))

(defn ensure-output-dirs [builds]
  (mapv ensure-output-dirs* builds)
  builds)

(defn figwheel-client-options [{:keys [figwheel]}]
  (if figwheel
    (if-not (map? figwheel) {} figwheel)
    {}))

(comment
  (figwheel-client-options {:figwheel {:hey 1}})

  (figwheel-client-options {:figwheel true})
  
  )

(defn fix-figwheel-symbol-keys [figwheel]
  (into {} (map (fn [[k v]] [k (if (symbol? v) (name v) v)]) figwheel)))

(defn append-build-id [figwheel build]
  (if (:id build)
    (assoc figwheel :build-id (:id build))
    figwheel))

(defn prep-build-for-figwheel-client [{:keys [figwheel] :as build}]
  (if figwheel
    (assoc build :figwheel
           (-> (figwheel-client-options build)
             (append-build-id build)
             (fix-figwheel-symbol-keys)))
    build))

(defn prep-builds-for-figwheel-client [builds]
  (mapv prep-build-for-figwheel-client builds))

(defn figwheel-build? [build]
  (and (= (get-in build [:build-options :optimizations]) :none)
       (:figwheel build)))

(defn update-figwheel-connect-options [figwheel-server build]
  (if (figwheel-build? build)
    (let [build (prep-build-for-figwheel-client build)]
      (if-not (get-in build [:figwheel :websocket-url]) ;; prefer 
        (let [host (or (get-in build [:figwheel :websocket-host]) "localhost")]
          (-> build
            (update-in [:figwheel] dissoc :websocket-host)
            (assoc-in [:figwheel :websocket-url]
                      (str "ws://" host ":" (:server-port figwheel-server) "/figwheel-ws"))))        
        build))
    build))

(comment
  (update-figwheel-connect-options {:port 5555} {:figwheel {:websocket-host "llllll"} :yeah 6})
  (update-figwheel-connect-options {:port 5555} {:figwheel {:websocket-host "llllll" :websocket-url "yep"} :yeah 6})
  (update-figwheel-connect-options {:port 5555} {:figwheel true})
  )

(comment
  (fix-figwheel-symbol-keys {:on-jsload 'asdfasdf :hey 5})
  (prep-build-for-figwheel-client {})
  (prep-build-for-figwheel-client { :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5}})
)

(defn prep-builds [builds]
  (-> builds
      map-to-vec-builds
      fix-builds
      prep-builds-for-figwheel-client
      ensure-output-dirs
      no-seqs))

(defn prep-options
  "Normalize various configuration input."
  [opts]
  (->> opts
       no-seqs
       (apply-to-key str :ring-handler)
       (apply-to-key str :http-server-root)       
       (apply-to-key str :open-file-command)
       (apply-to-key str :server-logfile)))
