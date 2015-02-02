(ns figwheel.client.file-reloading
  (:require
   [figwheel.client.utils :as utils :refer-macros [dev-assert]]
   [goog.Uri :as guri]
   [goog.string]
   [goog.net.jsloader :as loader]
   [clojure.string :as string]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(declare reload-file*)

(defonce ns-meta-data (atom {}))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (dev-assert (string? url))
  (.makeUnique (guri/parse url)))

(defn ns-to-js-file [ns]
  (dev-assert (string? ns))
  (str (string/replace ns "." "/") ".js"))

(defn resolve-ns [ns]
  (dev-assert (string? ns))
  (str (string/replace (.-basePath js/goog) #"(.*)goog/" #(str %2))
       (ns-to-js-file ns)))

;; still don't know how I feel about this
(defn patch-goog-base []
  (set! (.-isProvided_ js/goog) (fn [x] false))
  (when (or (nil? *loaded-libs*) (empty? *loaded-libs*))
    (set! *loaded-libs*
          (let [gntp (.. js/goog -dependencies_ -nameToPath)]
            (into #{}
                  (filter
                   (fn [name]
                     (aget (.. js/goog -dependencies_ -visited) (aget gntp name)))
                   (js-keys gntp))))))
  (set! (.-require js/goog)
        (fn [name reload]           
          (when (or (not (contains? *loaded-libs* name)) reload)
            (set! *loaded-libs* (conj (or *loaded-libs* #{}) name))
            (reload-file* (resolve-ns name)))))
  (set! (.-provide js/goog) (.-exportPath_ js/goog))
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) reload-file*))

(defmulti resolve-url :type)

(defmethod resolve-url :default [{:keys [file]}] file)

(defmethod resolve-url :namespace [{:keys [namespace]}]
  (dev-assert (string? namespace))
  (resolve-ns namespace))

(defmulti reload-base utils/host-env?)

(defmethod reload-base :node [request-url callback]
  (dev-assert (string? request-url) (not (nil? callback)))
  (let [root (string/join "/" (reverse (drop 2 (reverse (string/split js/__dirname "/")))))
        path (str root "/" request-url)]
    (aset (.-cache js/require) path nil)
    (callback (try
                (js/require path)
                (catch js/Error e
                  false)))))

(defmethod reload-base :html [request-url callback]
  (dev-assert (string? request-url) (not (nil? callback)))  
  (let [deferred (loader/load (add-cache-buster request-url)
                              #js { :cleanupWhenDone true })]
    (.addCallback deferred #(apply callback [true]))
    (.addErrback deferred #(apply callback [false]))))

(defn reload-file*
  ([request-url callback] (reload-base request-url callback))
  ([request-url] (reload-file* request-url identity)))

(defn reload-file [{:keys [request-url] :as file-msg} callback]
  (dev-assert (string? request-url)
              (map? file-msg)
              (not (nil? callback)))
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

(defn reload-file? [{:keys [request-url namespace dependency-file meta-data] :as file-msg}]
  (and 
   (or dependency-file
       (and meta-data (:figwheel-load meta-data))
       ;; IMPORTANT make sure this file is currently provided
       (contains? *loaded-libs* namespace)
       #_(.isProvided_ js/goog (name namespace)))
   (not (:figwheel-no-load (or meta-data {})))))

(defn js-reload [{:keys [request-url namespace meta-data] :as file-msg} callback]
  (when meta-data (swap! ns-meta-data assoc namespace meta-data))
  (if (reload-file? file-msg)
    (reload-file file-msg callback)
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
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as file-msg}]
  (assoc file-msg :request-url
         (if url-rewriter
           (url-rewriter file)
           (resolve-url file-msg))))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn eval-body [{:keys [eval-body file]}]
  (when (and eval-body (string? eval-body))
    (let [code eval-body]
      (try
        (utils/debug-prn (str "Evaling file " file))
        (js* "eval(~{code})")
        (catch :default e
          (utils/log :error (str "Unable to evaluate " file)))))))

(defn reload-js-files [{:keys [before-jsload on-jsload load-from-figwheel] :as opts} {:keys [files] :as msg}]
  (go
    (before-jsload files)
    ;; evaluate the eval bodies first
    (doseq [eval-body-file (filter #(:eval-body %) files)]
      (eval-body eval-body-file))
    (let [files'  (add-request-urls opts (filter #(not (:eval-body %)) files))
          res'    (<! (load-all-js-files files'))
          res     (filter :loaded-file res')
          files-not-loaded  (filter #(not (:loaded-file %)) res')]
      (when (not-empty res)
        (utils/log :debug "Figwheel: loaded these files")
        (utils/log (pr-str (map (fn [{:keys [namespace file]}]
                                  (if namespace
                                    (ns-to-js-file namespace)
                                    file)) res)))
        (js/setTimeout #(apply on-jsload [res]) 10))
      (when (not-empty files-not-loaded)
        (utils/log :debug "Figwheel: NOT loading files that haven't been required")
        (utils/log (str "not required:" (pr-str (map :file files-not-loaded))))))))

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
