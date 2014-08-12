(ns figwheel.client
  (:require
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [clojure.string :as string])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [figwheel.client :refer [defonce]]))

(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

(defn reload-host [{:keys [websocket-url]}]
  (-> websocket-url
      (string/replace-first #"^ws:" "")
      (string/replace-first #"^//" "")
      (string/split #"/")
      first))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (str url "?rel=" (.getTime (js/Date.))))

(defn js-reload [{:keys [request-url namespace dependency-file] :as msg} callback]
  (if (or dependency-file
            ;; IMPORTANT make sure this file is currently provided
          (.isProvided_ js/goog namespace))
    (.addCallback (loader/load (add-cache-buster request-url))
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

(defn reload-js-files [{:keys [before-jsload on-jsload] :as opts} {:keys [files]}]
  (go
   (before-jsload files)
   (let [files'  (add-request-urls opts files) 
         res     (<! (load-all-js-files files'))]
     (when (not-empty res)
       (.debug js/console "Figwheel: loaded these files")
       (.log js/console (prn-str (map :file res)))
       (<! (timeout 10)) ;; wait a beat before callback
       (apply on-jsload [res])))))

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

(defn compile-failed [fail-msg compile-fail-callback]
  (compile-fail-callback (dissoc fail-msg :msg-name)))

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

(defn watch-and-reload* [{:keys [retry-count websocket-url
                                 jsload-callback
                                 on-compile-fail] :as opts}]
    (.debug js/console "Figwheel: trying to open cljs reload socket")  
    (let [socket (js/WebSocket. websocket-url)]
      (set! (.-onmessage socket) (fn [msg-str]
                                   (let [msg (read-string (.-data msg-str))]
                                     #_(.log js/console (prn-str msg))
                                     (condp = (:msg-name msg)
                                       :files-changed  (reload-js-files opts msg)
                                       :css-files-changed (reload-css-files opts msg)
                                       :compile-failed    (compile-failed msg on-compile-fail)
                                       nil))))
      (set! (.-onopen socket)  (fn [x]
                                 (patch-goog-base)
                                 (.debug js/console "Figwheel: socket connection established")))
      (set! (.-onclose socket) (fn [x]
                                 (log opts "Figwheel: socket closed or failed to open")
                                 (when (> retry-count 0)
                                   (.setTimeout js/window
                                                (fn []
                                                  (watch-and-reload*
                                                   (assoc opts :retry-count (dec retry-count))))
                                                2000))))
      (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))))

(defn default-on-jsload [url]
  (.dispatchEvent (.querySelector js/document "body")
                  (js/CustomEvent. "figwheel.js-reload"
                                   (js-obj "detail" url))))

(defn get-essential-messages [ed]
  (when ed
    (cons (select-keys ed [:message :class])
          (get-essential-messages (:cause ed)))))

(defn error-msg-format [{:keys [message class]}] (str class " : " message))

(def format-messages (comp (partial map error-msg-format) get-essential-messages))

(defn default-on-compile-fail [{:keys [formatted-exception exception-data] :as ed}]
  (.debug js/console "Figwheel: Compile Exception")
  (doseq [msg (format-messages exception-data)]
    (.log js/console msg))
  ed)

(defn default-before-load [files]
  (.debug js/console "Figwheel: loading files")
  #_(.log js/console (prn-str (mapv :file files)))
  files)

(defn default-on-cssload [files]
  (.debug js/console "Figwheel: loaded CSS files")
  (.log js/console (prn-str (map :file files)))
  files)

(defn watch-and-reload-with-opts [opts]
  (defonce watch-and-reload-singleton
    (watch-and-reload*
     (merge { :retry-count 100 
              :jsload-callback default-on-jsload ;; *** deprecated
              :on-jsload (or (:jsload-callback opts) default-on-jsload)
              :on-cssload default-on-cssload
              :before-jsload default-before-load
              :on-compile-fail default-on-compile-fail
              :url-rewriter identity
              :websocket-url (str "ws://" js/location.host "/figwheel-ws")}
            opts))))

;; This takes keyword arguments
(defn watch-and-reload
  [& {:keys [] :as opts}]
  (watch-and-reload-with-opts opts))
