(ns figwheel.client.file-reloading
  (:require
   [figwheel.client.utils :as utils :refer-macros [dev-assert]]
   [goog.Uri :as guri]
   [goog.string]
   [goog.net.jsloader :as loader]
   [clojure.string :as string]
   [clojure.set :refer [difference]]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(declare reload-file* resolve-ns)

(defonce ns-meta-data (atom {}))

;; dependency resolution

(defonce dependency-cache (atom {}))

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

(defn not-start [n pre]
  (dev-assert (string? n) (string? pre))
  (not (.startsWith goog.string n pre)))

(defn invalidate-dependency-cache! []
  (reset! dependency-cache {}))

(defn relevant-nms []
  (filter (fn [n]
            (dev-assert (string? n))
            (reduce #(and %1 (not-start n %2)) true ["goog" "cljs" "clojure"]))
          (js-keys (.. js/goog -dependencies_ -nameToPath))))

(defn dependencies* []
  (dev-assert (map? @dependency-cache))
  (reduce (fn [acc-map nm]
            (let [pth (aget (.. js/goog -dependencies_ -nameToPath) nm)
                  reqs (js-keys (aget (.. js/goog -dependencies_ -requires) pth))]
              (reduce (fn [amm r] (assoc-in amm [r nm] true)) acc-map reqs)))
            {}
            (relevant-nms)))

(defn dependencies []
  (dev-assert (map? @dependency-cache))
  (if (not-empty @dependency-cache)
    @dependency-cache
    (let [deps (dependencies*)]
      (dev-assert (map? deps))
      (reset! dependency-cache deps)
      deps)))

(defn ns-that-depend-on [nm]
  (dev-assert (string? nm))
  (set (keys (get (dependencies) nm))))

(defn ancestor* [nm nm2]
  (dev-assert (string? nm) (string? nm2))
  (let [deps (ns-that-depend-on nm)]
    (dev-assert (set? deps))
    (if (empty? deps)
      false
      (if (get deps nm2)
        true
        (reduce #(or %1 %2) false
                (map #(ancestor* % nm2) deps))))))

(defn ancestor [nm nm2]
  (dev-assert (string? nm) (string? nm2) (map? @dependency-cache))
  ;; sharing a cache so that it gets blown away when deps are updated
  (let [cached (get-in @dependency-cache [:ancestor nm nm2])]
    (if (not (nil? cached))
      cached
      (let [res (ancestor* nm nm2)]
        (swap! dependency-cache assoc-in [:ancestor nm nm2] res)
        res))))

(defn topo-compare [nm nm2]
  (dev-assert (string? nm) (string? nm2))
  (cond
    (= nm nm2) 0
    (ancestor nm nm2) -1
    :else 1))

(defn topo-compare-file-msg [f1 f2]
  (dev-assert (namespace-file-map? f1) (namespace-file-map? f2))
  (topo-compare (:namespace f1) (:namespace f2)))

(defn topo-sort-files
  "Takes a list of file maps and returns a list of files in dep order."
  [files]
  (dev-assert (all? namespace-file-map? files))
  (sort topo-compare-file-msg files))

(defn store-meta-data-for-files! [files]
  (dev-assert (all? namespace-file-map? files))
  ;; store meta data
  (doseq [{:keys [namespace meta-data]}
          (filter #(and (:meta-data %) (:namespace %)) files)]
    (swap! ns-meta-data assoc namespace meta-data)))

(defn expand-files-to-include-deps [file-msgs]
  (dev-assert (all? namespace-file-map? file-msgs))
  (store-meta-data-for-files! file-msgs)
  (let [current-ns    (set (map :namespace file-msgs))
        dependent-ns  (set (mapcat ns-that-depend-on current-ns))
        additional-ns (difference dependent-ns current-ns)
        additional-files (map (fn [x] { :namespace x
                                       :meta-data (get @ns-meta-data x)
                                       :file (resolve-ns x)
                                       :type :namespace }) additional-ns)]
    (topo-sort-files (concat (set file-msgs)
                             additional-files))))

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

(defn reload-file? [{:keys [request-url namespace meta-data] :as file-msg}]
  (dev-assert (namespace-file-map? file-msg))
  (and 
   (or (and meta-data (:figwheel-load meta-data))
       ;; IMPORTANT make sure this file is currently provided
       (contains? *loaded-libs* namespace)
       #_(.isProvided_ js/goog (name namespace)))
   (not (:figwheel-no-load (or meta-data {})))))

(defn js-reload [{:keys [request-url namespace] :as file-msg} callback]
  (dev-assert (namespace-file-map? file-msg))
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
  (let [out (chan)]
    (go-loop [[f & fs] files]
      (if-not (nil? f)
        (do (put! out (<! (reload-js-file f)))
            (recur fs))
        (close! out)))
    (async/into [] out))
  ;; the following is a parallel load which is facter but non-deterministic
  #_(async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))


(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as file-msg}]
  (let [resolved-path (resolve-url file-msg)]
    (assoc file-msg :request-url
           (if url-rewriter
             (url-rewriter resolved-path)
             resolved-path))))

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
    ;; for now this is only for updating dependencies
    ;; we are not handling removals
    ;; TODO handle removals
    (let [eval-bodies (filter #(:eval-body %) files)]
      (when (not-empty eval-bodies)
        (doseq [eval-body-file eval-bodies]
          (eval-body eval-body-file))
        (invalidate-dependency-cache!)))

    (let [filtered-files (filter #(and (:namespace %)
                                       (not (:eval-body %)))
                                 files)
          all-files  (expand-files-to-include-deps filtered-files)
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
