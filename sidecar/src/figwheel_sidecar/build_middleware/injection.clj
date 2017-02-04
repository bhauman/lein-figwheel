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

(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
                                :on-message
                                :on-compile-fail
                                :on-compile-warning])

(defn figwheel-connect-ns-parts [{:keys [id]}]
  (cond-> ["figwheel" "connect"]
    (and id (name-like? id)) (conj (str "build-" (name id)))))

(defn underscore [cljs-path-name]
  (string/replace cljs-path-name "-" "_"))

(defn un-underscore [cljs-path-name]
  (string/replace cljs-path-name "_" "-"))

(defn apply-last [f parts]
  (if (= (count parts) 3)
    (update-in parts [2] f)
    parts))

(def figwheel-connect-ns-name
  (comp (partial string/join ".")
        (partial apply-last un-underscore)
        figwheel-connect-ns-parts))

(def figwheel-connect-ns-path
  (comp #(str % ".cljs")
        (partial string/join "/")
        (partial apply-last underscore)
        figwheel-connect-ns-parts))

#_(figwheel-connect-ns-path {:id :build-it})

(defn connect-script-temp-dir [build]
  (assert (:id build) (str "Following build needs an id: " build))
  (str "target/figwheel_temp/" (underscore (name (:id build)))))

(defn connect-script-path [build]
  (io/file (connect-script-temp-dir build) (figwheel-connect-ns-path build)))

(defn delete-connect-scripts! [builds]
  (doseq [b builds]
    (when (config/figwheel-build? b)
      (let [f (connect-script-path b)]
        (when (.exists f) (.delete f))))))

(defn extract-connection-requires [{:keys [figwheel] :as build}]
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

(defn extract-connection-script-required-ns [{:keys [figwheel] :as build}]
  (list 'ns (symbol (figwheel-connect-ns-name build))
        (cons :require
              (extract-connection-requires build))))

(defn hook-name-to-js [hook-name]
  (symbol
   (str "js/"
        (string/join "." (string/split (str hook-name) #"/")))))

(defn try-jsreload-hook [k hook-name]
  ;; change hook to js form to avoid compile warnings when it doesn't
  ;; exist, these compile warnings are confusing and prevent code loading
  (let [hook-name' (hook-name-to-js hook-name)]
    (list 'fn '[& x]
          (list 'if hook-name'
                (list 'apply hook-name' 'x)
                (list 'figwheel.client.utils/log :debug (str "Figwheel: " k " hook '" hook-name "' is missing"))))))

(defn extract-connection-script-figwheel-start [{:keys [figwheel]}]
  (let [func-map (select-keys figwheel figwheel-client-hook-keys)
        func-map (into {} (map (fn [[k v]] [k (try-jsreload-hook k v)]) func-map))
        res (merge figwheel func-map)]
    (list 'figwheel.client/start res)))

(defn extract-connection-devcards-start [{:keys [figwheel]}]
  (when (:devcards figwheel)
      (list 'devcards.core/start-devcard-ui!)))

(defn generate-connect-script [build]
  (vec (keep
        identity
        (list (extract-connection-script-required-ns build)
              (extract-connection-script-figwheel-start build)
              (extract-connection-devcards-start build)))))

#_(generate-connect-script {:id :asdf :build-options {:main 'example.core}})

(defn create-connect-script! [build]
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

(defn create-connect-script-if-needed! [build]
  (when (config/figwheel-build? build)
    (when-not (.exists (connect-script-path build))
      (create-connect-script! build))))

(defn add-connect-script! [figwheel-server build]
  (if (config/figwheel-build? build)
    (let [build (config/update-figwheel-connect-options figwheel-server build)
          devcards? (get-in build [:figwheel :devcards])]
      (create-connect-script-if-needed! build)
      (-> build
          ;; might want to add in devcards jar path here :)
          (update-in [:compile-paths]
                     ;; using the connect script instead of inline code because of two prominent
                     ;; CLJS bugs
                     ;; - another where the connection script isn't generated in a node env                     
                     ;; https://github.com/bhauman/lein-figwheel/issues/474
                     ;; - one where analysis cache is invlidated
                     ;; https://github.com/bhauman/lein-figwheel/issues/489
                     (fn [sp] (let [res (cons (connect-script-temp-dir build)
                                              #_(generate-connect-script build)
                                              sp)]
                                (vec (if-let [devcards-src (and devcards?
                                                                (cljs.env/with-compiler-env (:compiler-env build)
                                                                  (not (ana-api/find-ns 'devcards.core)))
                                                                (io/resource "devcards/core.cljs"))]
                                       (cons devcards-src res)
                                       res)))))
          ;; this needs to be in the (:options (:compiler-env build))
          #_(update-in [:build-options] (fn [bo] (if devcards?
                                                   (assoc bo :devcards true)
                                                   bo)))))
    build))

(defn esc-fmt [a & args]
  (apply format a (map pr-str args)))

(defn document-write-require-lib [munged-ns]
  (esc-fmt "\ndocument.write(%s);"
           (esc-fmt "<script>if (typeof goog != %s) { goog.require(%s); }</script>"
                    "undefined" (name munged-ns))))

(defn require-connection-script-js [build]
  (let [node?     (when-let [target (get-in build [:build-options :target])]
                    (= target :nodejs)) 
        main?     (get-in build [:build-options :main])
        output-to (get-in build [:build-options :output-to])
        munged-connect-script-ns (compiler/munge (figwheel-connect-ns-name build)) 
        line (if (and main? (not node?))
               (str
                (when (get-in build [:figwheel :devcards])
                  (document-write-require-lib 'devcards.core))
                (document-write-require-lib munged-connect-script-ns))
               ;; else
               (esc-fmt "\ngoog.require(%s);" munged-connect-script-ns))]
    (when output-to
      (if (and main? (not node?))
        (let [lines (string/split (slurp output-to) #"\n")]
          ;; require before app
          (spit output-to (string/join "\n" (concat (butlast lines) [line] [(last lines)]))))
        (spit output-to line :append true)))))

(defn append-connection-init! [build]
  (when (config/figwheel-build? build)
    (require-connection-script-js build)))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config] :as build-state}]
    (build-fn
     (assoc build-state
            :build-config (add-connect-script! figwheel-server build-config)))
    (append-connection-init! build-config)))
