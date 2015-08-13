(ns figwheel.client.file-reloading
  (:require
   [figwheel.client.utils :as utils :refer-macros [dev-assert]]
   [goog.Uri :as guri]
   [goog.string]
   [goog.object :as gobj]   
   [goog.net.jsloader :as loader]
   [goog.string :as gstring]
   [clojure.string :as string]
   
   [clojure.set :refer [difference]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog]))

(declare queued-file-reload reload-file* resolve-ns)

;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.js-reload", function (e) {console.log(e.detail);} );
(defn on-jsload-custom-event [url]
  (utils/dispatch-custom-event "figwheel.js-reload" url))

;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.before-js-reload", function (e) { console.log(e.detail);} );
(defn before-jsload-custom-event [files]
  (utils/dispatch-custom-event "figwheel.before-js-reload" files))

(defn all? [pred coll]
  (reduce #(and %1 %2) true (map pred coll)))

(defn namespace-file-map? [m]
  (or
   (and (map? m)
        (string? (:namespace m))
        (string? (:file m))
        (= (:type m)
           :namespace))
   (do
     (println "Error not namespace-file-map" (pr-str m))
     false)))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (dev-assert (string? url))
  (.makeUnique (guri/parse url)))

(defn ns-to-js-file [ns]
  (dev-assert (string? ns))
  (str (string/replace ns "." "/") ".js"))

(defn fix-node-request-url [url]
  (if (gstring/startsWith url "../")
    (string/replace url "../" "")
    (str "goog/" url)))

(def resolve-ns
  (condp = (utils/host-env?)
    :node
    (fn [ns]
      (dev-assert (string? ns))
      (fix-node-request-url (aget js/goog.dependencies_.nameToPath ns)))
    (fn [ns]
      (dev-assert (string? ns))
      (str (utils/base-url-path) "goog/"
           (aget js/goog.dependencies_.nameToPath ns)))))

(defn bootstrap-goog-base
  "Reusable browser REPL bootstrapping. Patches the essential functions
  in goog.base to support re-loading of namespaces after page load."
  []
  ;; Monkey-patch goog.provide if running under optimizations :none - David
  (when-not js/COMPILED
    (set! (.-require__ js/goog) js/goog.require)
    ;; suppress useless Google Closure error about duplicate provides
    (set! (.-isProvided__ js/goog) (.-isProvided_ js/goog))
    (set! (.-isProvided_ js/goog) (fn [name] false))
    ;; provide cljs.user
    (goog/constructNamespace_ "cljs.user")
    ;; we must reuse Closure library dev time dependency management, under namespace
    ;; reload scenarios we simply delete entries from the correct
    ;; private locations
    (set! (.-CLOSURE_IMPORT_SCRIPT goog/global) queued-file-reload)
    (set! (.-require js/goog)
          (fn [src reload]
            (when (= reload "reload-all")
              (set! (.-cljsReloadAll_ js/goog) true))
            (let [reload? (or reload (.-cljsReloadAll__ js/goog))]
              (when reload?
                (let [path (aget js/goog.dependencies_.nameToPath src)]
                  (gobj/remove js/goog.dependencies_.visited path)
                  (gobj/remove js/goog.dependencies_.written path)
                  (gobj/remove js/goog.dependencies_.written
                               (str js/goog.basePath path))))
              (let [ret (.require__ js/goog src)]
                (when (= reload "reload-all")
                  (set! (.-cljsReloadAll_ js/goog) false))
                ret))))))

(defn patch-goog-base []
  (defonce bootstrapped-cljs (do (bootstrap-goog-base) true)))

(defmulti resolve-url :type)
(defmethod resolve-url :default [{:keys [file]}] file)
(defmethod resolve-url :namespace [{:keys [namespace]}]
  (dev-assert (string? namespace))
  (resolve-ns namespace))


(def reload-file*
  (condp = (utils/host-env?)
    :node
    (fn [request-url callback]
      (dev-assert (string? request-url) (not (nil? callback)))
      (let [root (string/join "/" (reverse (drop 2 (reverse (string/split js/__dirname "/")))))
            path (str root "/" request-url)]
        (prn request-url)
        (aset (.-cache js/require) path nil)
        (callback (try
                    (js/require path)
                    (catch js/Error e
                      (utils/log :error (str  "Figwheel: Error loading file " path))
                      (utils/log :error (.-stack e))
                      false)))))
    :html (fn [request-url callback]
            (dev-assert (string? request-url) (not (nil? callback)))  
            (let [deferred (loader/load (add-cache-buster request-url)
                                        #js { :cleanupWhenDone true })]
              (.addCallback deferred #(apply callback [true]))
              (.addErrback deferred #(apply callback [false]))))
    (fn [a b] (throw "Reload not defined for this platform"))))

(defn reload-file [{:keys [request-url] :as file-msg} callback]
  (dev-assert (string? request-url)
              (not (nil? callback))
              (namespace-file-map? file-msg))
  (utils/debug-prn (str "FigWheel: Attempting to load " request-url))
  (reload-file* request-url
                (fn [success?]
                  (if success?
                    (do
                      (utils/debug-prn (str "FigWheel: Successfullly loaded " request-url))
                      (apply callback [(assoc file-msg :loaded-file true)]))
                    (do
                      (utils/log :error (str  "Figwheel: Error loading file " request-url))
                      (apply callback [file-msg]))))))

;; for goog.require consumption
(defonce reload-chan (chan))

(defonce on-load-callbacks (atom {}))

(defn blocking-load [url]
  (let [out (chan)]
    (reload-file
       {:request-url url}
       (fn [file-msg]
         (put! out file-msg)
         (close! out)))
    out))

(defonce reloader-loop
  (go-loop []
    (when-let [url (<! reload-chan)]
      (let [file-msg (<! (blocking-load url))]
        (when-let [callback (get @on-load-callbacks url)]
          (callback file-msg))
        (recur)))))

(defn queued-file-reload [url]
  (put! reload-chan url))

(defn require-with-callback [{:keys [request-url] :as file-msg} callback]
  (swap! on-load-callbacks assoc request-url
         (fn [file-msg'] (swap! on-load-callbacks dissoc request-url)
           (apply callback [(merge file-msg (select-keys file-msg' [:loaded-file]))])))
  ;; we are forcing reload here
  (js/goog.require (name (:namespace file-msg)) true))

(defn reload-file? [{:keys [namespace meta-data] :as file-msg}]
  (dev-assert (namespace-file-map? file-msg))
  (let [meta-data (or meta-data {})]
    (and
     (not (:figwheel-no-load meta-data))
     (or
      (:figwheel-always meta-data)
      (:figwheel-load meta-data)
      ;; might want to use .-visited here
      (goog/isProvided__ (name namespace))))))

(def js-reload*
  (if (= :node (utils/host-env?))
    reload-file
    require-with-callback))

(defn js-reload [{:keys [request-url namespace] :as file-msg} callback]
  (dev-assert (namespace-file-map? file-msg))
  (if (reload-file? file-msg)
    (js-reload* file-msg callback)
    (do
      (utils/debug-prn (str "Figwheel: Not trying to load file " request-url))
      (apply callback [file-msg]))))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js/setTimeout #(js-reload file-msg (fn [url]
                                          (patch-goog-base)
                                          (put! out url)
                                          (close! out))) 0)
    out))

(defn load-all-js-files
  "Returns a chanel with one collection of loaded filenames on it."
  [files]
  (let [out (chan)]
    (go-loop [[f & fs] files]
      (if-not (nil? f)
        (do (put! out (<! (reload-js-file f)))
            (recur fs))
        (close! out)))
    (async/into [] out)))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as file-msg}]
  (let [resolved-path (resolve-url file-msg)]
    (assoc file-msg :request-url
           (if url-rewriter
             (url-rewriter resolved-path)
             resolved-path))))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn eval-body [{:keys [eval-body file]} opts]
  (when (and eval-body (string? eval-body))
    (let [code eval-body]
      (try
        (utils/debug-prn (str "Evaling file " file))
        (utils/eval-helper code opts)
        (catch :default e
          (utils/log :error (str "Unable to evaluate " file)))))))

(defn reload-js-files [{:keys [before-jsload on-jsload] :as opts}
                       {:keys [files] :as msg}]
  (go
    (before-jsload files)
    (before-jsload-custom-event files)
    ;; evaluate the eval bodies first
    ;; for now this is only for updating dependencies
    ;; we are not handling removals
    ;; TODO handle removals
    (let [eval-bodies (filter #(:eval-body %) files)]
      (when (not-empty eval-bodies)
        (doseq [eval-body-file eval-bodies]
          (eval-body eval-body-file opts))))
    
    (let [all-files (filter #(and (:namespace %)
                                  (not (:eval-body %)))
                            files)
          files'  (add-request-urls opts all-files)
          res'    (<! (load-all-js-files files'))
          res     (filter :loaded-file res')
          files-not-loaded  (filter #(not (:loaded-file %)) res')]
      (when (not-empty res)
        (utils/log :debug "Figwheel: loaded these files")
        (utils/log (pr-str (map (fn [{:keys [namespace file]}]
                                  (if namespace
                                    (ns-to-js-file namespace)
                                    file)) res)))
        (js/setTimeout #(do
                          (on-jsload-custom-event res)
                          (apply on-jsload [res])) 10))
      (when (not-empty files-not-loaded)
        (utils/log :debug "Figwheel: NOT loading these files ")
        (let [{:keys [figwheel-no-load not-required]}
              (group-by
                     (fn [{:keys [meta-data]}]
                       (cond
                         (nil? meta-data) :not-required
                         (contains? meta-data :figwheel-no-load) :figwheel-no-load
                         :else :not-required))
                     files-not-loaded)]
          (when (not-empty figwheel-no-load)
            (utils/log (str "figwheel-no-load meta-data: "
                            (pr-str (map :file figwheel-no-load)))))
          (when (not-empty not-required)
            (utils/log (str "not required: " (pr-str (map :file not-required))))))))))

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice)
         (.getElementsByTagName js/document "link")))

(defn truncate-url [url]
  (-> (first (string/split url #"\?")) 
      (string/replace-first (str (.-protocol js/location) "//") "")
      (string/replace-first ".*://" "")
      (string/replace-first #"^//" "")         
      (string/replace-first #"[^\/]*" "")))

(defn matches-file?
  [{:keys [file]} link]
  (when-let [link-href (.-href link)]
    (let [match (string/join "/"
                         (take-while identity
                                     (map #(if (= %1 %2) %1 false)
                                          (reverse (string/split file "/"))
                                          (reverse (string/split (truncate-url link-href) "/")))))
          match-length (count match)
          file-name-length (count (last (string/split file "/")))]
      (when (>= match-length file-name-length) ;; has to match more than the file name length
        {:link link
         :link-href link-href
         :match-length match-length
         :current-url-length (count (truncate-url link-href))}))))

(defn get-correct-link [f-data]
  (when-let [res (first
                  (sort-by
                   (fn [{:keys [match-length current-url-length]}]
                     (- current-url-length match-length))
                   (keep #(matches-file? f-data %)
                         (current-links))))]
    (:link res)))

(defn clone-link [link url]
  (let [clone (.createElement js/document "link")]
    (set! (.-rel clone)      "stylesheet")
    (set! (.-media clone)    (.-media link))
    (set! (.-disabled clone) (.-disabled link))
    (set! (.-href clone)     (add-cache-buster url))
    clone))

(defn create-link [url]
  (let [link (.createElement js/document "link")]
    (set! (.-rel link)      "stylesheet")
    (set! (.-href link)     (add-cache-buster url))
    link))

(defn add-link-to-doc
  ([new-link]
     (.appendChild (aget (.getElementsByTagName js/document "head") 0)
                   new-link))
  ([orig-link klone]
     (let [parent (.-parentNode orig-link)]
       (if (= orig-link (.-lastChild parent))
         (.appendChild parent klone)
         (.insertBefore parent klone (.-nextSibling orig-link)))
       (js/setTimeout #(.removeChild parent orig-link) 300))))

(defn reload-css-file [{:keys [file request-url] :as f-data}]
  (if-let [link (get-correct-link f-data)]
    (add-link-to-doc link (clone-link link (.-href link)))
    (add-link-to-doc (create-link (or request-url file)))))

(defn reload-css-files [{:keys [on-cssload] :as opts} files-msg]
  (when (utils/html-env?)
    (doseq [f (add-request-urls opts (:files files-msg))]
      (reload-css-file f))
    (go
      (<! (timeout 100))
      (on-cssload (:files files-msg)))))
