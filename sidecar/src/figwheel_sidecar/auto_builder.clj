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

(defmulti report-exception (fn [exception cause] (:type cause)))

(defmethod report-exception :reader-exception [e {:keys [file line column]}]
  (println (format "ERROR: %s on file %s, line %d, column %d"
                   (some-> e (.getCause) (.getMessage))
                   file line column)))

(defmethod report-exception :default [e _]
  (stack/print-stack-trace e 30))

(defn handle-exceptions [figwheel-server {:keys [build-options exception id] :as build}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (let [cause (ex-data (.getCause exception))]
    (report-exception exception cause)
    (flush)
    #_(clj-stacktrace.repl/pst+ exception)
    (fig/compile-error-occured
      (merge-build-into-server-state figwheel-server build)
      exception
      cause)))

(defn warning [builder warn-handler]
  (fn [build]
    (binding [cljs.analyzer/*cljs-warning-handlers* (conj cljs.analyzer/*cljs-warning-handlers*
                                                          (warn-handler build))]
      (builder build))))

(defn default-build-options [builder default-options]
  (fn [build]
    (builder
     (update-in build [:build-options] (partial merge default-options)))))

;; connection

(defn connect-script-temp-dir [build]
  (str "target/figwheel_temp/" (name (:id build))))

(defn connect-script-path [build]
  (str (connect-script-temp-dir build) "/figwheel/connect.cljs"))

(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
                                :on-compile-fail
                                :on-compile-fail])

(defn extract-connection-script-required-ns [{:keys [figwheel]}]
  (let [names (vals (select-keys figwheel figwheel-client-hook-keys))]
    (list 'ns 'figwheel.connect
          (concat (list :require ['figwheel.client])
                  (map #(vector (symbol (namespace (symbol %))))
                       names)))))

(defn extract-connection-script-figwheel-start [{:keys [figwheel]}]
  (let [func-map (select-keys figwheel figwheel-client-hook-keys)
        func-map (into {} (map (fn [[k v]] [k (symbol v)]) func-map))
        res (merge figwheel func-map)]
    (list 'figwheel.client/start res)))

(comment

  (extract-connection-script-required-ns {:figwheel {:on-jsload "blah.blah/on-jsload"}})

  (extract-connection-script-required-ns {:figwheel {}})

  (extract-connection-script-figwheel-start {:figwheel {:on-jsload "blah.blah/on-jsload" :websocket-url "hey"}})
  
  )

(defn figwheel-build? [build]
  (and (= (get-in build [:build-options :optimizations]) :none)
       (:figwheel build)))

(defn create-connect-script! [build]
  ;;; consider doing this is the system temp dir
  (.mkdirs (io/file (connect-script-temp-dir build)))
  (let [temp-file (io/file (connect-script-path build))]
    (.deleteOnExit temp-file)
    (with-open [file (io/writer temp-file)]
      (binding [*out* file]
        (println
         (apply str (mapcat
                     prn-str
                     (list (extract-connection-script-required-ns build)
                           (extract-connection-script-figwheel-start build)
                           '(.log js/console "this should be working")))))))
    temp-file))

(defn create-connect-script-if-needed! [build]
  (when (figwheel-build? build)
    (when-not (.exists (io/file (connect-script-path build)))
      (create-connect-script! build))))

(defn insert-client-script [build]
  (create-connect-script-if-needed! build)
  build)

(defn insert-figwheel-client-script [builder]
  (fn [build] (builder (insert-client-script build))))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (default-build-options {:recompile-dependents false})
    (insert-figwheel-client-script)
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

(defn add-connect-script [build]
  ;; maybe should just add it to source paths here
  ;; if you create it once here it could be deleted by a lein clean
  (if (figwheel-build? build)
    (do
      ;; create a fresh one at the beginning of each autobuild
      (create-connect-script! build)
      (update-in build [:source-paths] conj (connect-script-temp-dir build)))
    build))

(defn update-figwheel-connect-options [figwheel-server build]
  (if (:figwheel build)
    (let [build (config/prep-build-for-figwheel-client build)]
      (if-let [host (get-in build [:figwheel :websocket-host])]
        (-> build
          (update-in [:figwheel] dissoc :websocket-host)
          (assoc-in [:figwheel :websocket-url]
                    (str "ws://" host ":" (:server-port figwheel-server) "/figwheel-ws")))
        build))
    build))

(comment
  
  (update-figwheel-client-options {:port 5555} {:figwheel {:websocket-host "llllll"} :yeah 6})

  (update-figwheel-client-options {:port 5555} {:figwheel true})

  )

(defn autobuild* [{:keys [builds figwheel-server]}]
  (let [builds (map (partial update-figwheel-connect-options figwheel-server) builds)]
    (auto/autobuild*
     {:builds  (mapv add-connect-script builds)
      :builder (builder figwheel-server)
      :each-iteration-hook (fn [_ build]
                             (fig/check-for-css-changes figwheel-server))})))

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
