(ns figwheel-sidecar.components.cljs-autobuild
  (:require
   [figwheel-sidecar.config :refer [add-compiler-env prep-build prepped? fetch-config]]
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]

   [figwheel-sidecar.build-hooks.injection :as injection] 

   [com.stuartsierra.component :as component]
   [cljs.closure]
   [cljs.build.api :as bapi]
   [clojure.java.io :as io]))

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (apply bapi/inputs (:source-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

(let [reset-color "\u001b[0m"
      foreground-green "\u001b[32m"
      elapsed
      (fn [started-at]
        (let [elapsed-us (- (System/currentTimeMillis) started-at)]
          (with-precision 2
            (str (/ (double elapsed-us) 1000) " seconds"))))]
  (defn figwheel-start-and-end-messages [build-fn]
    (fn [{:keys [build-config changed-files] :as build-state}]
      (let [started-at (System/currentTimeMillis)
            {:keys [build-options source-paths]} build-config
            {:keys [output-to]} build-options]
        ;; print start message
        (println (str reset-color "Compiling \""
                      output-to
                      "\" from " (pr-str source-paths) "..."))
        (flush)
                                        ; build
        (build-fn build-state)
                                        ; print end message
        (println (str foreground-green
                      "Successfully compiled \""
                      output-to
                      "\" in " (elapsed started-at) "." reset-color))
        (flush)))))

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     source-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

(defn- comp-build-fn [build-fn hook-fn]
  (let [build-hooks (-> build-fn meta :hooks)
        hook-name (-> hook-fn class .getName)]
    (-> build-fn
        hook-fn
        (with-meta {:hooks (conj build-hooks hook-name)}))))

(defrecord CLJSAutobuild [build-config]
  component/Lifecycle
  (start [this]
    (if-not (:file-watcher this)
      (do
        (println "Figwheel: Watching build -" (:id build-config))
        (flush)
        ;; setup
        (injection/delete-connect-scripts! [build-config])
        ;; TODO this should be conditional based on a flag
        #_(clean-cljs-build* (:build-options build-config))
        ;; initial build only needs the injection and the
        ;; start and end messages

        (let [log-writer (or (:log-writer this)
                             (io/writer "figwheel_server.log" :append true))

              components (->> this vals (filter #(satisfies? component/Lifecycle %)))
              hooks (->> components (mapv :cljsbuild/on-build) (filter some?))
              once-hooks (->> components (mapv :cljsbuild/on-first-build) (filter some?))

              first-cljs-build-fn (reduce comp-build-fn
                                          cljs-build
                                          (conj once-hooks figwheel-start-and-end-messages))
              cljs-build-fn (reduce comp-build-fn
                                    cljs-build
                                    (conj hooks figwheel-start-and-end-messages))]

          ;; build once before watching
          ;; tiny experience tweak
          ;; first build shouldn't send notifications
          (first-cljs-build-fn this)

          (assoc this
                 ;; for simple introspection
                 :cljs-autobuild true
                 :cljs-build-fn cljs-build-fn
                 :file-watcher
                 (watching/watch! (source-paths-that-affect-build build-config)
                          (fn [files]
                            (utils/sync-exec
                             (fn []
                               (binding [*out* log-writer
                                         *err* log-writer]
                                 (cljs-build-fn
                                  (assoc this
                                         :changed-files (map str files)))))))))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build -" (:id build-config))
      (flush)
      (watching/stop! (:file-watcher this)))
    (assoc this :file-watcher nil)))

(defn cljs-autobuild
  "  Creates a ClojureScript autobuilding component that watches
  ClojureScript source files for changes and then compiles them."
  [{:keys [build-config build-id] :as opts}]
  ;; do a little preparation of the build config just in case
  (let [build-config (or build-config
                         (and build-id
                              (->> (fetch-config)
                                   :all-builds
                                   (filter #(-> % :id (= build-id)))
                                   first)))
        build-config (if-not (prepped? build-config)
                       (prep-build build-config)
                       build-config)
        build-config (if-not (:compiler-env build-config)
                       (add-compiler-env build-config)
                       build-config)]
    (map->CLJSAutobuild {assoc opts :build-config build-config})))
