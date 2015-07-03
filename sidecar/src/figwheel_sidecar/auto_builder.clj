(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.config :as config]
   [cljs.repl]
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
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
  (assert (:id build) (str "Following build needs an id: " build))
  (str "target/figwheel_temp/" (name (:id build))))

(defn connect-script-path [build]
  (str (connect-script-temp-dir build) "/figwheel/connect.cljs"))

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

(comment

  (extract-connection-script-required-ns {:figwheel {:on-jsload "blah.blah/on-jsload"}})

  (extract-connection-script-required-ns {:figwheel {}})

  (extract-connection-script-figwheel-start {:figwheel {:on-jsload "blah.blah/on-jsload" :websocket-url "hey"}})

  )

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

;; TODO have figwheel script stay in memory

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
  (let [node? (and (:target build) (== (:target build) :nodejs)) 
        main? (get-in build [:build-options :main])
        output-to (get-in build [:build-options :output-to])
        line (if (and main? (not node?))
               (str
                (when (get-in build [:figwheel :devcards])
                  "\ndocument.write(\"<script>if (typeof goog != \\\"undefined\\\") { goog.require(\\\"devcards.core\\\"); }</script>\");")
                "\ndocument.write(\"<script>if (typeof goog != \\\"undefined\\\") { goog.require(\\\"figwheel.connect\\\"); }</script>\");")
               "\ngoog.require(\"figwheel.connect\");")]
    (when (and output-to (not node?))
      (if main?
        (let [lines (string/split (slurp output-to) #"\n")]
          ;; require before app
          (spit output-to (string/join "\n" (concat (butlast lines) [line] [(last lines)]))))
        (spit output-to line :append true)))))

(defn append-connection-init! [build]
  (when (config/figwheel-build? build)
    (require-connection-script-js build)))

(defn insert-figwheel-connect-script [builder figwheel-server]
  (fn [build]
    (let [res (builder (add-connect-script! figwheel-server build))]
      (append-connection-init! build)
      res)))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (default-build-options {:recompile-dependents false})
    
    (insert-figwheel-connect-script figwheel-server)
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

(defn delete-connect-scripts! [builds]
  (doseq [b builds]
    (when (config/figwheel-build? b)
      (let [f (io/file (connect-script-path b))]
        (when (.exists f) (.delete f))))))

(defn autobuild* [{:keys [builds figwheel-server]}]
  (delete-connect-scripts! builds)
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
