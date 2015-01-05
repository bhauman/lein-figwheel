(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]))

(defn check-changes [figwheel-server build]
  (let [{:keys [additional-changed-ns build-options id old-mtimes new-mtimes]} build]
    (fig/check-for-changes (merge figwheel-server
                                  (if id {:build-id id} {})
                                  (select-keys build-options [:output-dir :output-to]))
                           old-mtimes
                           new-mtimes
                           additional-changed-ns)))

(defn handle-exceptions [figwheel-server {:keys [build-options exception]}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (clj-stacktrace.repl/pst+ exception)
  (fig/compile-error-occured figwheel-server exception))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (auto/warning (auto/warning-message-handler
                   (partial fig/compile-warning-occured figwheel-server)))
    auto/time-build
    (auto/after auto/compile-success)    
    (auto/after (partial check-changes figwheel-server))
    (auto/error (partial handle-exceptions figwheel-server))
    (auto/before auto/compile-start)))

(defn autobuild* [{:keys [builds figwheel-server]}]
  (auto/autobuild*
   {:builds  builds
    :builder (builder figwheel-server)
    :each-iteration-hook (fn [_] (fig/check-for-css-changes figwheel-server))}))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))
