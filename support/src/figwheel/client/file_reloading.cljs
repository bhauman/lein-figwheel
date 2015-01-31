(ns figwheel.client.file-reloading
  (:require
   [figwheel.client.utils :as utils]
   [goog.Uri :as guri]
   [goog.string]
   [goog.net.jsloader :as loader]
   [clojure.string :as string]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

;; super tricky hack to get goog to load newly required files

(declare add-cache-buster)

(def ^:dynamic *load-from-figwheel* true)

(defn figwheel-closure-import-script [src]
  (if (.inHtmlDocument_ js/goog)
    (do
      #_(.log js/console "Figwheel: latently loading required file " src )
      (loader/load (add-cache-buster src))
      true)
    false))

(defn patch-goog-base []
  (set! (.-provide js/goog) (.-exportPath_ js/goog))
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) figwheel-closure-import-script))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

(defn reload-host [{:keys [websocket-url]}]
  (-> websocket-url
      (string/replace-first #"^wss?:" "")
      (string/replace-first #"^//" "")
      (string/split #"/")
      first))

(defonce ns-meta-data (atom {}))

(defn get-meta-data-for-ns [ns]
  (get ns-meta-data ns))

(defn resolve-ns [ns]
  (str (string/replace-first (.-basePath js/goog) "/goog" "")
       (string/replace ns "." "/")
       ".js"))

(def goog-deps-path (str (.-basePath js/goog) "deps.js"))

(defn get-main-file-path
  "Very unreliable way to get the main js file for reloading. 
   Fortunately its not the end of the world if this file doesn't 
   get reloaded."
  []
  (let [sel  (str "script[src='" goog-deps-path "']")]
    (when-let [el (.querySelector js/document sel)]
      (when-let [el (.-nextElementSibling el)]
        (.getAttribute el "src")))))

(let [main-file-path (get-main-file-path)]
  (defn resolve-deps-path [{:keys [file request-url]}]
    (cond
      (re-matches #".*goog/deps\.js" file) goog-deps-path
      (and main-file-path
           (apply = (mapv #(last (string/split % "/")) [main-file-path file])))
      main-file-path
      :else (do
              (utils/debug-prn (str "No deps match:" file))
              request-url))))

(defn resolve-url [{:keys [request-url namespace dependency-file] :as msg}]
  (if (not *load-from-figwheel*)
    (do
      (utils/debug-prn "Not loading from figwheel")
      (if dependency-file
        (resolve-deps-path msg)
        (resolve-ns namespace)))
    request-url))

(defn js-reload [{:keys [request-url namespace dependency-file meta-data] :as msg} callback]
  (swap! ns-meta-data assoc namespace meta-data)
  (let [request-url (resolve-url msg)]
    (if (and
         (or dependency-file
             (and meta-data (:figwheel-load meta-data))
             ;; IMPORTANT make sure this file is currently provided
           (.isProvided_ js/goog (name namespace)))
         (not (:figwheel-no-load (or meta-data {}))))
      (let [req-url (add-cache-buster request-url)
            _ (utils/debug-prn (str "FigWheel: Attempting to load " req-url))
            deferred (loader/load req-url #js { :cleanupWhenDone true })]
        (.addCallback deferred
                      #(do
                         (utils/debug-prn (str "FigWheel: Successfullly loaded " request-url))
                         (apply callback [(assoc msg :loaded-file true)])))
        (.addErrback deferred
                     (fn [eb]
                       (utils/debug-prn (.-stack eb))
                       (.error js/console "Figwheel: Error loading file with script tag:" request-url))))
      (do
        (utils/debug-prn (str "Figwheel: Not trying to load file " request-url))
        (apply callback [msg])))))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    ;; escape context for better error reporting
    (js/setTimeout #(js-reload file-msg (fn [url]
                                          (patch-goog-base)
                                          (put! out url)
                                          (close! out))) 0)
    out))

(defn load-all-js-files
  "Returns a chanel with one collection of loaded filenames on it."
  [files]
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as d}]
  (->> file
       (str "//" (reload-host opts))
       url-rewriter
       (assoc d :request-url)))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn reload-js-files [{:keys [before-jsload on-jsload load-from-figwheel] :as opts} {:keys [files] :as msg}]
  (go
    (before-jsload files)
    (with-redefs [*load-from-figwheel* load-from-figwheel]
      (let [files'  (add-request-urls opts files)
            res'    (<! (load-all-js-files files'))
            res     (filter :loaded-file res')
            files-not-loaded  (filter #(not (:loaded-file %)) res')]
        (when (not-empty res)
          (.debug js/console "Figwheel: loaded these files")
          (.log js/console (pr-str (map :file res)))
          (js/setTimeout #(apply on-jsload [res]) 10))
        (when (not-empty files-not-loaded)
          (.debug js/console "Figwheel: NOT loading files that haven't been required")
          (.log js/console "not required:" (pr-str (map :file files-not-loaded))))))))

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice) (.getElementsByTagName js/document "link")))

(defn truncate-url [url]
  (-> (first (string/split url #"\?")) 
      (string/replace-first (str (.-protocol js/location) "//") "")
      (string/replace-first ".*://" "")
      (string/replace-first #"^//" "")         
      (string/replace-first #"[^\/]*" "")))

(defn matches-file? [{:keys [file request-url]} link-href]
  (let [trunc-href (truncate-url link-href)]
    (or (= file trunc-href)
        (= (truncate-url request-url) trunc-href))))

#_(defn matches-file-new? [{:keys [file] } link-href]
    (.endsWith goog.string file (truncate-url link-href)))

(defn get-correct-link [f-data]
  (some (fn [l]
          (when (matches-file? f-data (.-href l)) l))
        (current-links)))

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
       (go
        (<! (timeout 200))
        (.removeChild parent orig-link)))))

(defn reload-css-file [{:keys [file request-url] :as f-data}]
  (if-let [link (get-correct-link f-data)]
    (add-link-to-doc link (clone-link link request-url))
    (add-link-to-doc (create-link request-url))))

(defn reload-css-files [{:keys [on-cssload] :as opts} files-msg]
  (doseq [f (add-request-urls opts (:files files-msg))]
    (reload-css-file f))
  (go
   (<! (timeout 100))
   (on-cssload (:files files-msg))))

