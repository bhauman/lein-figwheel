(ns figwheel-sidecar.components.cljs-autobuild
  (:require
   [figwheel-sidecar.config :refer [prep-build prepped?]]
   [figwheel-sidecar.build-utils :as butils]
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]

      ;; build hooks
   [figwheel-sidecar.build-middleware.injection :as injection]
   [figwheel-sidecar.build-middleware.notifications :as notifications]
   [figwheel-sidecar.build-middleware.clj-reloading :as clj-reloading]
   [figwheel-sidecar.build-middleware.javascript-reloading :as javascript-reloading]

   [com.stuartsierra.component :as component]
   [cljs.closure]
   [cljs.build.api :as bapi]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]))

;; TODO can I run this without a figwheel server??
;; that would make this component much more useful

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (apply bapi/inputs (:source-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

;; TODO use ansi color lib here
(let [reset-color "\u001b[0m"
      foreground-red "\u001b[31m"
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
        (try
          ; build
          (build-fn build-state)
          ; print end message
          (println (str foreground-green
                        "Successfully compiled \""
                        output-to
                        "\" in " (elapsed started-at) "." reset-color))
          (flush)
          (catch Throwable e
            (println (str foreground-red
                          "Failed to compile \""
                          output-to
                          "\" in " (elapsed started-at) "." reset-color))
            (flush)
            (throw e)))))))


(defn handle-notify-command [build-state s]
  (when-let [notify-command (-> build-state :build-config :notify-command)]
    (apply sh (concat notify-command [s]))))

(defn notify-command-hook [build-fn]
  (fn [build-state]
    (try
      (build-fn build-state)
      (handle-notify-command build-state (str "Successfully compiled ClojureScript build: "
                                              (name (-> build-state :build-config :id))))
      (catch Throwable e
        (handle-notify-command build-state (str "Failed compiling ClojureScript build: "
                                                (name (-> build-state :build-config :id))))
        (throw e)))))

(def figwheel-build
  (-> cljs-build
      injection/hook
      notify-command-hook
      clj-reloading/hook
      javascript-reloading/hook
      figwheel-start-and-end-messages
      notifications/hook))

(def figwheel-build-without-javascript-reloading
  (-> cljs-build
      injection/hook
      notify-command-hook      
      clj-reloading/hook
      figwheel-start-and-end-messages
      notifications/hook))

(def figwheel-build-without-clj-reloading
  (-> cljs-build
      injection/hook
      notify-command-hook      
      javascript-reloading/hook
      figwheel-start-and-end-messages
      notifications/hook))

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     source-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

;; this is just used for the initial build
(defn catch-print-hook
  "Build middleware hook that catches and prints errors."
  [build-fn]
  (fn [build-state]
    (try
      (build-fn build-state)
      (catch Throwable e
        #_(prn (ex-data (.getCause e)))
        (notifications/report-exception e (ex-data (.getCause e)))
        ;; this only applies if :output-to doesn't exist
        (flush)
        (let [output-to-filepath
              (get-in build-state [:build-config :build-options :output-to])]
          ;; I am assuming for now that if the output file exists
          ;; the user is in an interactive development environment
          ;; if not we need to stop and let them know
          (when-not (.exists (io/file output-to-filepath))
            (throw (ex-info "---- Initial Figwheel ClojureScript Compilation Failed ---- \nWe need a successful initial build for Figwheel to connect correctly.\n"
                        {:reason :initial-cljs-build-exception
                         :escape-system-exceptions true}
                        e))))))))

(defn extract-cljs-build-fn
  [{:keys [figwheel-server] :as cljs-autobuild}]
  (or (:cljs-build-fn cljs-autobuild)
      (:cljs-build-fn figwheel-server)
      ;; if no figwheel server
      ;; default build should be standard
      ;; cljs build
      (if figwheel-server
        figwheel-build
        (figwheel-start-and-end-messages cljs-build))))

(defn execute-build
  [{:keys [figwheel-server] :as cljs-autobuild} files]
  (let [log-writer (or (:log-writer cljs-autobuild)
                       (:log-writer figwheel-server)
                       (io/writer "figwheel_server.log" :append true))
        cljs-build-fn (extract-cljs-build-fn cljs-autobuild)]
    (utils/sync-exec
     (fn []
       (binding [*out* log-writer
                 *err* log-writer]
         (cljs-build-fn
          (assoc cljs-autobuild
                 :changed-files (map str files))))))))

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

        (let [cljs-build-fn (extract-cljs-build-fn this)]
          ;; build once before watching
          ;; tiny experience tweak
          ;; first build shouldn't send notifications
          ;; this is mainly to discard spurious warnings that originate
          ;; in projects that are required by this project but are not generated by local
          ;; code.
          ;; This is a bad solution
          ;; A better solution is to differentiate in these warnings and have a system
          ;; that says wether a warning is a blocking warning
          ((if (= cljs-build-fn figwheel-build)
             ;; the order here is very very important
             ;; must think about the exception flow
             ;; exceptions must skip over code that needs the build to succeed
             ;; also consider the messages that the user recieves 
             (-> cljs-build
                 injection/hook
                 notify-command-hook
                 figwheel-start-and-end-messages
                 catch-print-hook)
             cljs-build-fn) this)
          (assoc this
                 ;; for simple introspection
                 :cljs-autobuild true
                 :file-watcher
                 (watching/watch!
                  (:hawk-options figwheel-server)
                  (source-paths-that-affect-build build-config)
                  (partial execute-build this)))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build -" (:id build-config))
      (flush)
      (watching/stop! (:file-watcher this)))
    (dissoc this :file-watcher)))

(defn cljs-autobuild
  "Creates a ClojureScript autobuilding component that watches
  ClojureScript source files for changes and then compiles them. This
  component relies on a :figwheel-server component and this component
  should satisfy the
  figwheel-sidecar.components.figwheel-server/ChannelServer protocol.

  You need to at least supply this component with a :build-config.

  You may optionally supply a :cljs-build-fn for this component to
  use."
  [{:keys [build-config] :as opts}]
  (let [build-config (if-not (prepped? build-config)
                       (prep-build build-config)
                       build-config)
        build-config (if-not (:compiler-env build-config)
                       (butils/add-compiler-env build-config)
                       build-config)]
    (map->CLJSAutobuild (assoc opts :build-config build-config))))
