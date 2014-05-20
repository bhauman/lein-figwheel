(ns figwheel.client
  (:require
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [clojure.string :as string])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [figwheel.client :refer [defonce]]))

(def log-style "color:rgb(0,128,0);")

(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (str url "?rel=" (.getTime (js/Date.))))

(defn js-reload [{:keys [file namespace dependency-file] :as msg} callback]
  (if (or dependency-file
            ;; IMPORTANT make sure this file is currently provided
          (.isProvided_ js/goog namespace))
    (.addCallback (loader/load (add-cache-buster file))
                  #(apply callback [file]))
    (apply callback [false])))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js-reload file-msg (fn [url] (put! out url) (close! out)))
    out))

(defn load-all-js-files [files]
  "Returns a chanel with one collection of loaded filenames on it."
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn reload-js-files [{:keys [files]} callback]
  (go
   (let [res (<! (load-all-js-files files))]
     (when (not-empty res)
       (.log js/console "%cFigwheel: loading these files" log-style )
       (.log js/console (clj->js res))
       (<! (timeout 10)) ;; wait a beat before callback
       (apply callback [res])))))

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice) (.getElementsByTagName js/document "link")))

(defn matches-file? [css-path link-href]
  (= css-path
     (-> (first (string/split link-href #"\?")) 
         (string/replace-first (str (.-protocol js/location) "//") "")
         (string/replace-first (.-host js/location) ""))))

(defn get-correct-link [css-path]
  (some (fn [l]
          (when (matches-file? css-path (.-href l)) l))
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

(defn reload-css-file [{:keys [file]}]
  (if-let [link (get-correct-link file)]
    (add-link-to-doc link (clone-link link file))
    (add-link-to-doc (create-link file))))

(defn reload-css-files [files-msg jsload-callback]
  (doseq [f (:files files-msg)]
    (reload-css-file f))
  (.log js/console "%cFigwheel: loaded CSS files" log-style)
  (.log js/console (clj->js (map :file (:files files-msg))))
  ;; really not sure about this
  ;; do we really need to call a callback here
  ;; I think a separate callback for CSS reloads may make
  ;; sense but I doubt it's needed at all
  ;; (<! (timeout 100))
  ;; (jsload-callback nil)
  )

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

(defn watch-and-reload* [{:keys [retry-count websocket-url jsload-callback] :as opts}]
    (.log js/console "%cFigwheel: trying to open cljs reload socket" log-style)  
    (let [socket (js/WebSocket. websocket-url)]
      (set! (.-onmessage socket) (fn [msg-str]
                                   (let [msg (read-string (.-data msg-str))]
                                     (condp = (:msg-name msg)
                                       :files-changed (reload-js-files msg jsload-callback)
                                       :css-files-changed (reload-css-files msg jsload-callback)
                                       nil))))
      (set! (.-onopen socket)  (fn [x]
                                 (patch-goog-base)
                                 (.log js/console "%cFigwheel: socket connection established" log-style)))
      (set! (.-onclose socket) (fn [x]
                                 (log opts "Figwheel: socket closed or failed to open")
                                 (when (> retry-count 0)
                                   (.setTimeout js/window
                                                (fn []
                                                  (watch-and-reload*
                                                   (assoc opts :retry-count (dec retry-count))))
                                                2000))))
      (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))))

(defn default-jsload-callback [url]
  (.dispatchEvent (.querySelector js/document "body")
                  (js/CustomEvent. "figwheel.js-reload"
                                   (js-obj "detail" url))))

(defn watch-and-reload-with-opts [opts]
  (defonce watch-and-reload-singleton
    (watch-and-reload*
     (merge { :retry-count 100 
              :jsload-callback default-jsload-callback
              :websocket-url (str "ws:" js/location.host "/figwheel-ws")}
            opts))))

(defn watch-and-reload [& opts]
  (watch-and-reload-with-opts opts))

