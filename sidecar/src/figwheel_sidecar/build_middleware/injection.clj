(ns figwheel-sidecar.build-middleware.injection
  (:require
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :refer [name-like?]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cljs.env :as env]
   [cljs.compiler :as compiler]
   [cljs.analyzer.api :as ana-api]))

#_(remove-ns 'figwheel-sidecar.build-middleware.injection)

#_(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
                                :on-message
                                :on-compile-fail
                                :on-compile-warning])

#_(defn figwheel-connect-ns-parts [{:keys [id]}]
  (cond-> ["figwheel" "connect"]
    (and id (name-like? id)) (conj (str "build-" (name id)))))

#_(defn underscore [cljs-path-name]
  (string/replace cljs-path-name "-" "_"))

#_(defn un-underscore [cljs-path-name]
  (string/replace cljs-path-name "_" "-"))

#_(defn apply-last [f parts]
  (if (= (count parts) 3)
    (update-in parts [2] f)
    parts))

#_(def figwheel-connect-ns-name
  (comp (partial string/join ".")
        (partial apply-last un-underscore)
        figwheel-connect-ns-parts))

#_(def figwheel-connect-ns-path
  (comp #(str % ".cljs")
        (partial string/join "/")
        (partial apply-last underscore)
        figwheel-connect-ns-parts))

#_(figwheel-connect-ns-path {:id :build-it})

#_(defn connect-script-temp-dir [build]
  (assert (:id build) (str "Following build needs an id: " build))
  (str "target/figwheel_temp/" (underscore (name (:id build)))))

#_(defn connect-script-path [build]
  (io/file (connect-script-temp-dir build) (figwheel-connect-ns-path build)))

#_(defn delete-connect-scripts! [builds]
  (doseq [b builds]
    (when (config/figwheel-build? b)
      (let [f (connect-script-path b)]
        (when (.exists f) (.delete f))))))

#_(defn extract-connection-requires [{:keys [figwheel] :as build}]
  (let [names (set
               (map #(symbol (namespace (symbol %)))
                    (vals (select-keys figwheel figwheel-client-hook-keys))))
        ;; if there is a main defined then add it
        main-ns  (get-in build [:build-options :main])
        names (if main-ns
                (conj names (symbol (str main-ns)))
                names)
        names (map vector (conj names 'figwheel.client 'figwheel.client.utils))
        names (if (:devcards figwheel)
                (conj names '[devcards.core :include-macros true])
                names)]
    names))

#_(figwheel-connect-ns-name {:id :asdf-asdf})

#_(defn extract-connection-script-required-ns [{:keys [figwheel] :as build}]
  (list 'ns (symbol (figwheel-connect-ns-name build))
        (cons :require
              (extract-connection-requires build))))

#_(defn hook-name-to-js [hook-name]
  (symbol
   (str "js/"
        (string/join "." (string/split (str hook-name) #"/")))))

#_(defn try-jsreload-hook [k hook-name]
  ;; change hook to js form to avoid compile warnings when it doesn't
  ;; exist, these compile warnings are confusing and prevent code loading
  (let [hook-name' (hook-name-to-js hook-name)]
    (list 'fn '[& x]
          (list 'if hook-name'
                (list 'apply hook-name' 'x)
                (list 'figwheel.client.utils/log :debug (str "Figwheel: " k " hook '" hook-name "' is missing"))))))

#_(defn extract-connection-script-figwheel-start [{:keys [figwheel]}]
  (let [func-map (select-keys figwheel figwheel-client-hook-keys)
        func-map (into {} (map (fn [[k v]] [k (try-jsreload-hook k v)]) func-map))
        res (merge figwheel func-map)]
    (list 'figwheel.client/start res)))

#_(defn extract-connection-devcards-start [{:keys [figwheel]}]
  (when (:devcards figwheel)
      (list 'devcards.core/start-devcard-ui!)))

#_(defn generate-connect-script [build]
  (vec (keep
        identity
        (list (extract-connection-script-required-ns build)
              (extract-connection-script-figwheel-start build)
              (extract-connection-devcards-start build)))))

#_(generate-connect-script {:id :asdf :build-options {:main 'example.core }})

#_(defn create-connect-script! [build]
  ;;; consider doing this is the system temp dir
  (let [temp-file (connect-script-path build)]
    (.mkdirs (.getParentFile temp-file))
    (.deleteOnExit temp-file)
    (with-open [file (io/writer temp-file)]
      (binding [*out* file]
        (println
         (apply str (mapcat
                     prn-str
                     (generate-connect-script build))))))
    temp-file))

#_(defn create-connect-script-if-needed! [build]
  (when (config/figwheel-build? build)
    (when-not (.exists (connect-script-path build))
      (create-connect-script! build))))

(defn- node? [build]
  (when-let [target (get-in build [:build-options :target])]
    (= target :nodejs)))

(defn- has-main? [build]
  (get-in build [:build-options :main]))

(defn- has-modules? [build]
  (get-in build [:build-options :modules]))

(defn- has-output-to? [build]
  (get-in build [:build-options :output-to]))

#_(defn- modules-output-to [build]
  (when (get-in build [:build-options :modules])
    (get-in build [:build-options :modules :cljs-base :output-to]
            (str (io/file (get-in build [:build-options :output-dir]
                                  "out") "cljs_base.js" )))))

#_(defn- build-output-to [build]
  (or (modules-output-to build)
      (get-in build [:build-options :output-to])))


#_(build-output-to {:build-options {:output-to "resources/public/asdf.js"
                                  :output-dir "resources/public/js"
                                  :modules {:cljs-base {:output-to "baser.js"}}}})


(defn add-connect-script! [figwheel-server build]
  ;; cannot just supply arbitrary connection code to the compiler because of the following issues
  ;; CLJS bugs
  ;; - another where the connection script isn't generated in a node env
  ;; https://github.com/bhauman/lein-figwheel/issues/474
  ;; - one where analysis cache is invlidated
  ;; https://github.com/bhauman/lein-figwheel/issues/489
  (if (config/figwheel-build? build)
    (let [build (config/update-figwheel-connect-options figwheel-server build)
          devcards? (get-in build [:figwheel :devcards])]
      (-> build
          ;; TODO this only needs to be done in certain cases
          ;; TODO eventually we will be loading devcards with 'devcards.preload
          (update-in [:build-options :preloads] (fn [x] (if devcards?
                                                          ((fnil conj []) x 'devcards.core)
                                                          x)))
          ;; only in cases where there is a not an main for now
          (update-in [:build-options :preloads]
                     (fnil conj [])
                     (if (and (has-main? build)
                              (not (has-modules? build))
                              (not (node? build)))
                       'figwheel.connect
                       'figwheel.preload))
          (update-in [:build-options :external-config :figwheel/config] #(if % % (get build :figwheel {})))
          ;; might want to add in devcards jar path here :)
          (update-in [:compile-paths]
                     (fn [res] (vec (if-let [devcards-src (and devcards?
                                                               (cljs.env/with-compiler-env (:compiler-env build)
                                                                 (not (ana-api/find-ns 'devcards.core)))
                                                               (io/resource "devcards/core.cljs"))]
                                      (cons devcards-src res)
                                      res))))
          ;; this needs to be in the (:options (:compiler-env build))
          #_(update-in [:build-options] (fn [bo] (if devcards?
                                                   (assoc bo :devcards true)
                                                   bo)))))
    build))


;; continuing to append require for the html document case to enable
;; document.onload callbacks to work
(defn esc-fmt [a & args]
  (apply format a (map pr-str args)))

#_(defn document-write-require-lib [munged-ns]
  (esc-fmt "\ndocument.write(%s);"
           (esc-fmt "<script>goog.require(%s);</script>" (name munged-ns))))

#_(defn append-require-to-output-to [build munged-ns]
  (let [output-to (has-output-to? build)
        line (if (and (has-main? build) (not (node? build)))
               (str (document-write-require-lib munged-ns))
               (esc-fmt "\ngoog.require(%s);"   munged-ns))]
    (when (and output-to (.exists (io/file output-to)))
      (spit output-to line :append true))))

(defn document-write-src-script [src]
  (esc-fmt "\ndocument.write(%s);"
           (format "<script>%s</script>" src)))

(defn append-src-script [build src-code]
  (let [output-to (has-output-to? build)
        line (if (and (has-main? build) (not (node? build)))
               (str (document-write-src-script src-code))
               (format "\n%s" src-code))]
    (when (and output-to (.exists (io/file output-to)))
      (spit output-to line :append true))))

(defn append-connection-init! [build]
  (when (and (config/figwheel-build? build)
             (has-main? build)
             (not (has-modules? build))
             (not (node? build)))
    (append-src-script build "figwheel.connect.start();")))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config] :as build-state}]
    (build-fn
     (assoc build-state
            :build-config (add-connect-script! figwheel-server build-config)))
    (append-connection-init! build-config)))
