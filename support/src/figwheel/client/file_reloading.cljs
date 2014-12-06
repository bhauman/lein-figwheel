(ns figwheel.client.file-reloading
  (:require
   [goog.Uri :as guri]
   [goog.net.jsloader :as loader]
   [clojure.string :as string]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  )

;; this assumes no query string on url
(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

(defn reload-host [{:keys [websocket-url]}]
  (-> websocket-url
      (string/replace-first #"^wss?:" "")
      (string/replace-first #"^//" "")
      (string/split #"/")
      first))

(defn js-reload [{:keys [request-url namespace dependency-file] :as msg} callback]
  (if (or dependency-file
            ;; IMPORTANT make sure this file is currently provided
          (.isProvided_ js/goog namespace))
    (.addCallback (loader/load (add-cache-buster request-url) #js { :cleanupWhenDone true })
                  #(apply callback [msg]))
    (apply callback [msg])))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js-reload file-msg (fn [url] (put! out url) (close! out)))
    out))

(defn load-all-js-files [files]
  "Returns a chanel with one collection of loaded filenames on it."
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as d}]
  (->> file
       (str "//" (reload-host opts))
       url-rewriter
       (assoc d :request-url)))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn reload-js-files [{:keys [before-jsload on-jsload] :as opts} {:keys [files] :as msg}]
  (go
   (before-jsload files)
   (let [files'  (add-request-urls opts files) 
         res     (<! (load-all-js-files files'))]
     (when (not-empty res)
       (.debug js/console "Figwheel: loaded these files")
       (.log js/console (pr-str (map :file res)))
       (js/setTimeout #(apply on-jsload [res]) 10)))))


;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice) (.getElementsByTagName js/document "link")))

(defn truncate-url [url]
  (-> (first (string/split url #"\?")) 
      (string/replace-first (str (.-protocol js/location) "//") "")
      (string/replace-first "http://" "")
      (string/replace-first "https://" "")      
      (string/replace-first #"^//" "")         
      (string/replace-first #"[^\/]*" "")))

(defn matches-file? [{:keys [file request-url]} link-href]
  (let [trunc-href (truncate-url link-href)]
    (or (= file trunc-href)
        (= (truncate-url request-url) trunc-href))))

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

;; super tricky hack to get goog to load newly required files

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
