(ns figwheel-sidecar.components.cljs-autobuild
  (:require
   [figwheel-sidecar.config :as config :refer [prep-build prepped?]]
   [figwheel-sidecar.build-utils :as butils]
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]
   [figwheel-sidecar.cljs-utils.exception-parsing :as cljs-ex]
   [strictly-specking-standalone.ansi-util :refer [with-color with-color-when color-text]]

      ;; build hooks
   [figwheel-sidecar.build-middleware.injection :as injection]
   [figwheel-sidecar.build-middleware.notifications :as notifications]
   [figwheel-sidecar.build-middleware.clj-reloading :as clj-reloading]
   [figwheel-sidecar.build-middleware.javascript-reloading :as javascript-reloading]
   [figwheel-sidecar.build-middleware.stamp-and-clean :as stamp-and-clean]

   [com.stuartsierra.component :as component]
   [cljs.closure]
   [cljs.build.api :as bapi]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.java.browse]))

;; TODO can I run this without a figwheel server??
;; that would make this component much more useful

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (apply bapi/inputs (:compile-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

(defn time-elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

(defn figwheel-start-and-end-messages [build-fn]
  (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
    (let [started-at (System/currentTimeMillis)
          {:keys [build-options compile-paths id]} build-config
          {:keys [output-to output-dir]} build-options]
      ;; print start message
      (println (str "Compiling build :" id " to \""
                    (or output-to output-dir)
                    "\" from " (pr-str compile-paths) "..."))
      (try
        (build-fn build-state)
        (println (color-text (str "Successfully compiled build :" id " to \""
                                  (or output-to output-dir)
                                  "\" in " (time-elapsed started-at) ".")
                             :green))
        (catch Throwable e
          (println (color-text (str
                                "Failed to compile build :" id " from "
                                (pr-str compile-paths)
                                " in " (time-elapsed started-at) ".")
                               :red))
          (throw e))
        (finally (flush))))))

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

(defn color-output [build-fn]
  (fn [{:keys [figwheel-server] :as build-state}]
    (with-color-when (config/use-color? figwheel-server)
      (build-fn build-state))))

;; !! assume nothing about the order of middleware !!
;; if the order changes re-verify the function of each part
(def figwheel-build
  (-> cljs-build
      injection/hook
      notify-command-hook
      figwheel-start-and-end-messages
      notifications/hook
      ;; the following two hooks have to be called before the notifications
      ;; a they modify the message on the way down
      clj-reloading/hook
      javascript-reloading/hook
      color-output))

(def figwheel-build-without-javascript-reloading
  (-> cljs-build
      injection/hook
      notify-command-hook
      figwheel-start-and-end-messages
      notifications/hook
      clj-reloading/hook
      color-output))

(def figwheel-build-without-clj-reloading
  (-> cljs-build
      injection/hook
      notify-command-hook
      figwheel-start-and-end-messages
      notifications/hook
      javascript-reloading/hook
      color-output))

(defn source-paths-that-affect-build [{:keys [build-options watch-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     watch-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

;; open url build middleware

(def open-urls-once
  (memoize
   (fn [urls]
     (future
       (Thread/sleep 1000)
       (doseq [url urls]
         (clojure.java.browse/browse-url url))))))

(defn open-urls [build-state]
  (when-let [urls (not-empty
                   (-> build-state
                       :build-config
                       :figwheel
                       :open-urls
                       set))]
    (open-urls-once urls)))

(defn open-urls-hook [build-fn]
  (fn [build-state]
    (build-fn build-state)
    (open-urls build-state)))

(defn extract-data-for-deadman-app [build-config e]
  (-> build-config
      :figwheel
      (select-keys [:websocket-host :websocket-url :build-id])
      (assoc
       :autoload false
       :initial-messages [{:msg-name :compile-failed
                           :exception-data (cljs-ex/parse-exception e)}])))

(defn deadman-header-comment []
  "/* FIGWHEEL BAD COMPILE RECOVERY APPLICATION */
/* NOT YOUR COMPILED CLOJURESCRIPT - TEMPORARY FIGWHEEL GENERATED PROGRAM
 * This is only created in the case where the compile fails and you don't have a
 * generated output-to file.
 */")

(defn create-deadman-app-js [build-config output-to-filepath exception]
  (println (color-text (str "Figwheel: initial compile failed - outputting temporary helper application to "
                            output-to-filepath)
                       :magenta))
  (let [data (pr-str (pr-str (extract-data-for-deadman-app build-config exception)))]
    (spit (io/file output-to-filepath)
          (str
           (deadman-header-comment)
           "FIGWHEEL_CLIENT_CONFIGURATION="
           data
           ";\n"
           (slurp #_(io/file (str "../sidecar/resources/compiled-utils/figwheel-helper-deploy.js"))
                  (io/resource "compiled-utils/figwheel-helper-deploy.js"))))))

(defn deadman-output-to-file? [output-to-file]
  (when (.exists (io/file output-to-file))
    (when-let [first-line (first (line-seq (io/reader output-to-file)))]
      (re-matches #".*FIGWHEEL BAD COMPILE.*"  (str first-line)))))

;; this is just used for the initial build
(defn catch-print-hook
  "Build middleware hook that catches and prints errors."
  [build-fn]
  (fn [build-state]
    (try
      (build-fn build-state)
      (catch Throwable e
        (cljs-ex/print-exception e)
        (flush)
        (let [build-config (:build-config build-state)
              output-to-filepath (get-in build-config [:build-options :output-to])]
          ;; I am assuming for now that if the output file exists
          ;; the user is in an interactive development environment
          ;; if not we need to stop and let them know
          ;; this only applies if :output-to doesn't exist
          (when (or (not (.exists (io/file output-to-filepath)))
                    (deadman-output-to-file? output-to-filepath))
            ;; must be a fighweel build
            ;; and not a node build
            ;; and not a modules based build
            (if (and (:figwheel build-config)
                     (-> build-config :build-options :main)
                     (not (= :nodejs (:target build-config)))
                     (not (:modules build-config)))
              (create-deadman-app-js build-config output-to-filepath e)
              (throw
               (ex-info (str "---- Initial Figwheel ClojureScript Compilation Failed ---- \n"
                             "We need a successful initial build for Figwheel to connect correctly.\n")
                        {:reason :initial-cljs-build-exception
                         :escape-system-exceptions true}
                        e)))))))))

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
                       #_(io/writer "figwheel_server.log" :append true))
        cljs-build-fn (extract-cljs-build-fn cljs-autobuild)]
    (utils/sync-exec
     (fn []
       (utils/bind-logging
        log-writer
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
        ;; Not the best but just to make sure there isn't a lingering figwheel connect script
        #_(injection/delete-connect-scripts! [build-config])

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
                 catch-print-hook
                 open-urls-hook
                 stamp-and-clean/hook
                 color-output)
             cljs-build-fn) this)
          (assoc this
                 ;; for simple introspection
                 :cljs-autobuild true
                 :file-watcher
                 (watching/watch!
                  (:hawk-options figwheel-server)
                  (source-paths-that-affect-build build-config)
                  (partial execute-build this)
                  (:wait-time-ms figwheel-server)))))
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
