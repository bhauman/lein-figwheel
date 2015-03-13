(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.config :as config]
   [cljs.repl]
   [cljs.analyzer :as ana]
   [cljs.env]
   #_[clj-stacktrace.repl]
   [clojure.stacktrace :as stack]   
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.set :refer [intersection]]
   [cljsbuild.util :as util]))

(defn notify-cljs [command message]
  (when (seq (:shell command))
    (try
      (util/sh (update-in command [:shell] (fn [old] (concat old [message]))))
      (catch Throwable e
        (println (auto/red "Error running :notify-command:"))
        (stack/print-stack-trace e 30)
        (flush)
        #_(clj-stacktrace.repl/pst+ e)))))

(defn notify-on-complete [{:keys [build-options parsed-notify-command]}]
  (let [{:keys [output-to]} build-options]
    (notify-cljs
     parsed-notify-command
     (str "Successfully compiled " output-to))))

(defn merge-build-into-server-state [figwheel-server {:keys [id build-options]}]
  (merge figwheel-server
         (if id {:build-id id} {})
         (select-keys build-options [:output-dir :output-to :recompile-dependents])))

(defn check-changes [figwheel-server build]
  (let [{:keys [additional-changed-ns build-options id old-mtimes new-mtimes]} build]
    (binding [cljs.env/*compiler* (:compiler-env build)]
      (fig/check-for-changes
       (merge-build-into-server-state figwheel-server build)
       old-mtimes
       new-mtimes
       additional-changed-ns))))

(defn handle-exceptions [figwheel-server {:keys [build-options exception id] :as build}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (stack/print-stack-trace exception 30)
  (flush)
  #_(clj-stacktrace.repl/pst+ exception)
  (fig/compile-error-occured
   (merge-build-into-server-state figwheel-server build)
   exception))

(defn warning [builder warn-handler]
  (fn [build]
    (binding [cljs.analyzer/*cljs-warning-handlers* (conj cljs.analyzer/*cljs-warning-handlers*
                                                          (warn-handler build))]
      (builder build))))

(defn default-build-options [builder default-options]
  (fn [build]
    (builder
     (update-in build [:build-options] (partial merge default-options)))))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (default-build-options {:recompile-dependents false})
    (warning
     (fn [build]
       (auto/warning-message-handler
        (partial fig/compile-warning-occured
                 (merge-build-into-server-state figwheel-server build)))))
    auto/time-build
    (auto/after auto/compile-success)
    (auto/after (partial check-changes figwheel-server))
    (auto/after notify-on-complete)
    (auto/error (partial handle-exceptions figwheel-server))
    (auto/before auto/compile-start)))

(defn autobuild* [{:keys [builds figwheel-server]}]
  (auto/autobuild*
   {:builds  builds
    :builder (builder figwheel-server)
    :each-iteration-hook (fn [_ build]
                           (fig/check-for-css-changes figwheel-server))}))

(defn check-autobuild-config [all-builds build-ids figwheel-server]
  (let [builds (config/narrow-builds* all-builds build-ids)]
    (config/check-config figwheel-server builds :print-warning true)))

(defn autobuild-ids [{:keys [all-builds build-ids figwheel-server]}]
  (let [builds (config/narrow-builds* all-builds build-ids)
        errors (config/check-config figwheel-server builds :print-warning true)]
    (if (empty? errors)
      (do
        (println (str "Figwheel: focusing on build-ids ("
                      (string/join " " (map :id builds)) ")"))
        (autobuild* {:builds builds
                     :figwheel-server figwheel-server}))
      (do
        (mapv println errors)
        false))))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))

(comment
  
  (def builds [{ :id "example"
                 :source-paths ["src" "../support/src"]
                 :build-options { :output-to "resources/public/js/compiled/example.js"
                                  :output-dir "resources/public/js/compiled/out"
                                  :source-map true
                                  :cache-analysis true
                                  ;; :reload-non-macro-clj-files false
                                  :optimizations :none}}])

  (def env-builds (map (fn [b] (assoc b :compiler-env
                                      (cljs.env/default-compiler-env
                                        (:compiler b))))
                        builds))

  (def figwheel-server (fig/start-server))
  
  (fig/stop-server figwheel-server)
  
  (def bb (autobuild* {:builds env-builds
                       :figwheel-server figwheel-server}))
  
  (auto/stop-autobuild! bb)

  (fig-repl/eval-js figwheel-server "1 + 1")

  (def build-options (:build-options (first builds)))
  
  #_(cljs.repl/repl (repl-env figwheel-server) )
)
