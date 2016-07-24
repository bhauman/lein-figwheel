(ns figwheel-sidecar.build-middleware.injection
  (:require
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :refer [name-like?]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cljs.env :as env]   
   [cljs.analyzer.api :as ana-api]))

#_(remove-ns 'figwheel-sidecar.build-middleware.injection)

(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
                                :on-message
                                :on-compile-fail
                                :on-compile-warning])

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

(defn figwheel-connect-ns-name [{:keys [id]}]
  (cond-> "figwheel.connect"
    (and id (name-like? id)) (str "." (name id))))

#_(figwheel-connect-ns-name {})

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

(defn add-connect-script! [figwheel-server build]
  (if (config/figwheel-build? build)
    (let [build (config/update-figwheel-connect-options figwheel-server build)
          devcards? (get-in build [:figwheel :devcards])]
      (-> build
          ;; might want to add in devcards jar path here :)
          (update-in [:source-paths] (fn [sp] (let [res (cons (generate-connect-script build) sp)]
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
        connect-script-ns (figwheel-connect-ns-name build)
        line (if (and main? (not node?))
               (str
                (when (get-in build [:figwheel :devcards])
                  (document-write-require-lib 'devcards.core))
                (document-write-require-lib connect-script-ns))
               ;; else
               (esc-fmt "\ngoog.require(%s);" connect-script-ns))]
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
