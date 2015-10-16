(ns figwheel-sidecar.build-hooks.injection
  (:require
   [figwheel-sidecar.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cljs.env :as env]   
   [cljs.analyzer.api :as ana-api]))

(defn connect-script-temp-dir [build]
  (assert (:id build) (str "Following build needs an id: " build))
  (str "target/figwheel_temp/" (name (:id build))))

(defn connect-script-path [build]
  (str (connect-script-temp-dir build) "/figwheel/connect.cljs"))

(defn delete-connect-scripts! [builds]
  (doseq [b builds]
    (when (config/figwheel-build? b)
      (let [f (io/file (connect-script-path b))]
        (when (.exists f) (.delete f))))))

(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
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

(defn extract-connection-script-required-ns [{:keys [figwheel] :as build}]
  (list 'ns 'figwheel.connect
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

(defn create-connect-script! [build]
  ;;; consider doing this is the system temp dir
  (let [temp-file (io/file (connect-script-path build))]
    (.mkdirs (.getParentFile temp-file))
    (.deleteOnExit temp-file)
    (with-open [file (io/writer temp-file)]
      (binding [*out* file]
        (println
         (apply str (mapcat
                     prn-str
                     (keep
                      identity
                      (list (extract-connection-script-required-ns build)
                            (extract-connection-script-figwheel-start build)
                            (extract-connection-devcards-start build))))))))
    temp-file))

(defn create-connect-script-if-needed! [build]
  (when (config/figwheel-build? build)
    (when-not (.exists (io/file (connect-script-path build)))
      (create-connect-script! build))))

(defn add-connect-script! [figwheel-server build]
  (if (config/figwheel-build? build)
    (let [build (config/update-figwheel-connect-options figwheel-server build)
          devcards? (get-in build [:figwheel :devcards])]
      (create-connect-script-if-needed! build)
      (-> build
        ;; might want to add in devcards jar path here :)
        (update-in [:source-paths] (fn [sp] (let [res (cons (connect-script-temp-dir build) sp)]
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

(defn require-connection-script-js [build]
  (let [node? (when-let [target (get-in build [:build-options :target])]
                (= target :nodejs)) 
        main? (get-in build [:build-options :main])
        output-to (get-in build [:build-options :output-to])
        line (if (and main? (not node?))
               (str
                (when (get-in build [:figwheel :devcards])
                  "\ndocument.write(\"<script>if (typeof goog != \\\"undefined\\\") { goog.require(\\\"devcards.core\\\"); }</script>\");")
                "\ndocument.write(\"<script>if (typeof goog != \\\"undefined\\\") { goog.require(\\\"figwheel.connect\\\"); }</script>\");")
               "\ngoog.require(\"figwheel.connect\");")]
    (when output-to
      (if (and main? (not node?))
        (let [lines (string/split (slurp output-to) #"\n")]
          ;; require before app
          (spit output-to (string/join "\n" (concat (butlast lines) [line] [(last lines)]))))
        (spit output-to line :append true)))))

(defn append-connection-init! [build]
  (when (config/figwheel-build? build)
    (require-connection-script-js build)))

(defn build-hook [build-fn]
  (fn [{:keys [figwheel-server build-config] :as build-state}]
    (build-fn
     (assoc build-state
            :build-config (add-connect-script! figwheel-server build-config)))
    (append-connection-init! build-config)))
