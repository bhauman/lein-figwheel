(ns figwheel.main.css-reload
  (:require
   [clojure.string :as string]
   #?@(:cljs [[goog.Uri :as guri]
              [goog.log :as glog]
              [goog.object :as gobj]]
       :clj [[clojure.data.json :as json]
             [cljs.env]
             [cljs.repl]
             [clojure.java.io :as io]
             [figwheel.main.watching :as fww]
             [figwheel.main.util :as fw-util]])
   )
  (:import #?@(:cljs [[goog Promise]
                      goog.debug.Console])))

#?(:cljs

   (do

;; --------------------------------------------------
;; Logging
;; --------------------------------------------------
;;
;; Levels
;; goog.debug.Logger.Level.(SEVERE WARNING INFO CONFIG FINE FINER FINEST)
;;
;; set level (.setLevel logger goog.debug.Logger.Level.INFO)
;; disable   (.setCapturing log-console false)

(defonce logger (glog/getLogger "Figwheel CSS Reload"))

(defn ^:export console-logging []
  (when-not (gobj/get goog.debug.Console "instance")
    (let [c (goog.debug.Console.)]
      ;; don't display time
      (doto (.getFormatter c)
        (gobj/set "showAbsoluteTime" false)
        (gobj/set "showRelativeTime" false))
      (gobj/set goog.debug.Console "instance" c)
      c))
  (when-let [console-instance (gobj/get goog.debug.Console "instance")]
    (.setCapturing console-instance true)
    true))

(defonce log-console (console-logging))

(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

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
  [file link]
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

(defn get-correct-link [file]
  (when-let [res (first
                  (sort-by
                   (fn [{:keys [match-length current-url-length]}]
                     (- current-url-length match-length))
                   (keep #(matches-file? file %)
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

(defn add-link-to-document [orig-link klone finished-fn]
  (let [parent (.-parentNode orig-link)]
    (if (= orig-link (.-lastChild parent))
      (.appendChild parent klone)
      (.insertBefore parent klone (.-nextSibling orig-link)))
    ;; prevent css removal flash
    (js/setTimeout #(do
                      (.removeChild parent orig-link)
                      (finished-fn))
                   300)))

(defonce reload-css-deferred-chain (atom (Promise. #(%1 []))))

(defn reload-css-file [file fin]
  (if-let [link (get-correct-link file)]
    (add-link-to-document link (clone-link link (.-href link))
                          #(fin file))
    (fin nil)))

(defn conj-reload-prom [prom file]
  (.then prom
         (fn [files]
           (Promise. (fn [succ fail]
                       (reload-css-file file
                                        (fn [f]
                                          (succ (if f
                                                  (conj files f)
                                                  files)))))))))

(defn dispatch-on-css-load [files]
  (.dispatchEvent
   js/document.body
   (doto (js/Event. "figwheel.after-css-load" js/document.body)
     (gobj/add "data" {:css-files files}))))

(defn reload-css-files* [files on-cssload]
  (doseq [file files]
    (swap! reload-css-deferred-chain conj-reload-prom file))
  (swap! reload-css-deferred-chain
         (fn [prom]
           (.then prom
                  (fn [loaded-files]
                    (when (not-empty loaded-files)
                      (glog/info logger (str "loaded " (pr-str loaded-files)))
                      (dispatch-on-css-load loaded-files))
                    (when-let [not-loaded (not-empty (remove (set loaded-files) (set files)))]
                      (glog/warning logger (str "Unable to reload " (pr-str not-loaded))))
                    ;; reset
                    [])))))


(defn reload-css-files [{:keys [on-cssload]} files]
  (when (not (nil? goog/global.document))
    (when-let [files' (not-empty (distinct files))]
      (reload-css-files* files' on-cssload))))

;;takes an array of css files, relativized with forward slash path-separators
(defn ^:export reload-css-files-remote [files-array]
  (reload-css-files {} files-array)
  true)

)

   :clj
   (do

(defn client-eval [code]
  (when-not (string/blank? code)
    (cljs.repl/-evaluate
     cljs.repl/*repl-env*
     "<cljs repl>" 1
     code)))

(defn reload-css-files [files]
  (when-not (empty? files)
    (client-eval
     (format "figwheel.main.css_reload.reload_css_files_remote(%s);"
             (json/write-str files)))))

(defn prep-css-file-path [file]
  (->> file
       fw-util/relativized-path-parts
       (string/join "/")))

;; repl-env needs to be bound
(defn start* [paths]
  (fww/add-watch!
   ::watcher
   {:paths paths
    :filter (fww/suffix-filter #{"css"})
    :handler (fww/throttle
              50
              (bound-fn [evts]
                (when-let [files (not-empty (mapv (comp prep-css-file-path :file) evts))]
                  (reload-css-files files))))}))

(defn stop* []
  (let [remove-watch! (resolve 'figwheel.main/remove-watch!)]
    (remove-watch! ::watcher)))

(defmacro start [paths] (start* paths) nil)

(defmacro stop [] (stop*) nil)

     )

   )
