(ns figwheel-sidecar.components.cljs-autobuild
  (:require
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.watching :as watching :refer [watcher]]
   [figwheel-sidecar.utils :as utils]

      ;; build hooks
   [figwheel-sidecar.build-hooks.injection :as injection] 
   [figwheel-sidecar.build-hooks.notifications :as notifications]
   [figwheel-sidecar.build-hooks.clj-reloading :as clj-reloading]
   [figwheel-sidecar.build-hooks.javascript-reloading :as javascript-reloading]   
   
   [com.stuartsierra.component :as component]
   [cljs.closure]
   [cljs.build.api :as bapi]
   [clojure.java.io :as io]))


(defrecord CompilableSourcePaths [paths]
  cljs.closure/Compilable
  (-compile [_ opts]
    (reduce (fn [accum v]
              (let [o (cljs.closure/-compile v opts)]
                (if (seq? o)
                  (concat accum o)
                  (conj accum o))))
            []
            paths)))

;; bapi/inputs should work but something wierd is happening
(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (CompilableSourcePaths. (:source-paths build-config))
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
    (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
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

(def figwheel-build
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      clj-reloading/build-hook
      javascript-reloading/build-hook
      figwheel-start-and-end-messages))

(def figwheel-build-without-javascript-reloading
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      clj-reloading/build-hook
      figwheel-start-and-end-messages))

(def figwheel-build-without-clj-reloading
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      javascript-reloading/build-hook
      figwheel-start-and-end-messages))

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     source-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

(defn build-handler [{:keys [figwheel-server build-config] :as watcher}
                     cljs-build-fn
                     files]
  (cljs-build-fn (assoc watcher :changed-files (map str files))))

(defrecord CLJSAutobuild [build-config figwheel-server]
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
        ((-> cljs-build
             injection/build-hook
             figwheel-start-and-end-messages) this)
        (let [log-writer (or (:log-writer this)
                             (:log-writer figwheel-server)
                             (io/writer "figwheel_server.log" :append true))
              cljs-build-fn (or (:cljs-build-fn this)
                                (:cljs-build-fn figwheel-server)
                                figwheel-build)]
          (assoc this
                 :file-watcher
                 (watcher (source-paths-that-affect-build build-config)
                          (fn [files]
                            (utils/sync-exec
                             (fn []
                               (binding [*out* log-writer
                                         *err* log-writer]
                                 (#'build-handler this cljs-build-fn files)))))))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build -" (:id build-config))
      (flush)
      (watching/stop! (:file-watcher this)))
    (dissoc this :file-watcher)))

(defn cljs-autobuild [build-config]
  (map->CLJSAutobuild {:build-config build-config}))
