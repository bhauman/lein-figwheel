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

(declare queued-file-reload)

(defonce figwheel-meta-pragmas (atom {}))

;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.js-reload", function (e) {console.log(e.detail);} );
(defn on-jsload-custom-event [url]
  (utils/dispatch-custom-event "figwheel.js-reload" url))

;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.before-js-reload", function (e) { console.log(e.detail);} );
(defn before-jsload-custom-event [files]
  (utils/dispatch-custom-event "figwheel.before-js-reload" files))

;; you can listen to this event easily like so:
;; document.body.addEventListener("figwheel.css-reload", function (e) {console.log(e.detail);} );
(defn on-cssload-custom-event [files]
  (utils/dispatch-custom-event "figwheel.css-reload" files))


#_(defn all? [pred coll]
  (reduce #(and %1 %2) true (map pred coll)))

(defn namespace-file-map? [m]
  (or
   (and (map? m)
        (string? (:namespace m))
        (or (nil? (:file m))
            (string? (:file m)))
        (= (:type m)
           :namespace))
   (do
     (println "Error not namespace-file-map" (pr-str m))
     false)))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (dev-assert (string? url))
  (.makeUnique (guri/parse url)))

(defn name->path [ns]
  (dev-assert (string? ns))
  (aget js/goog.dependencies_.nameToPath ns))

(defn provided? [ns]
  (aget js/goog.dependencies_.written (name->path ns)))

;; this is pretty simple, not sure how brittle it is 
(defn fix-node-request-url [url]
  (dev-assert (string? url))
  (if (gstring/startsWith url "../")
    (string/replace url "../" "")
    (str "goog/" url)))

(defn immutable-ns? [name]
  (or (#{"goog"
         "cljs.core"
         "an.existing.path"
         "dup.base"
         "far.out"
         "ns"
         "someprotopackage.TestPackageTypes"
         "svgpan.SvgPan"
         "testDep.bar"} name)
      (some
       (partial goog.string/startsWith name)
       ["goog." "cljs." "clojure." "fake." "proto2."])))

(defn get-requires [ns]
  (->> ns
    name->path
    (aget js/goog.dependencies_.requires)
    (gobj/getKeys)
    (filter #(not (immutable-ns? %)))
    set))

(defonce dependency-data (atom {:pathToName {} :dependents {}}))

(defn path-to-name! [path name]
  (swap! dependency-data update-in [:pathToName path] (fnil clojure.set/union #{}) #{name}))

(defn setup-path->name!
  "Setup a path to name dependencies map.
   That goes from path -> #{ ns-names }"
  []
  ;; we only need this for dependents
  (let [nameToPath (gobj/filter js/goog.dependencies_.nameToPath
                                (fn [v k o] (gstring/startsWith v "../")))]
    (gobj/forEach nameToPath (fn [v k o] (path-to-name! v k)))))

(defn path->name
  "returns a set of namespaces defined by a path"
  [path]
  (get-in @dependency-data [:pathToName path]))

(defn name-to-parent! [ns parent-ns]
  (swap! dependency-data update-in [:dependents ns] (fnil clojure.set/union #{}) #{parent-ns}))

(defn setup-ns->dependents!
  "This reverses the goog.dependencies_.requires for looking up ns-dependents."
  []
  (let [requires (gobj/filter js/goog.dependencies_.requires 
                              (fn [v k o] (gstring/startsWith k "../")))]
    (gobj/forEach
     requires
     (fn [v k _]
       (gobj/forEach
        v
        (fn [v' k' _]
          (doseq [n (path->name k)]
            (name-to-parent! k' n))))))))

(defn ns->dependents [ns]
  (get-in @dependency-data [:dependents ns]))

(defn build-topo-sort [get-deps]
  (let [get-deps (memoize get-deps)]
    (letfn [(topo-sort-helper* [x depth state]
              (let [deps (get-deps x)]
                (when-not (empty? deps) (topo-sort* deps depth state))))
            (topo-sort*
              ([deps]
               (topo-sort* deps 0 (atom (sorted-map))))
              ([deps depth state]
               (swap! state update-in [depth] (fnil into #{}) deps)
               (doseq [dep deps]
                 (topo-sort-helper* dep (inc depth) state))
               (when (= depth 0)
                 (elim-dups* (reverse (vals @state))))))
            (elim-dups* [[x & xs]]
              (if (nil? x)
                (list)
                (cons x (elim-dups* (map #(difference % x) xs)))))]
      topo-sort*)))

(defn get-all-dependencies [ns]
  (let [topo-sort' (build-topo-sort get-requires)]
    (apply concat (topo-sort' (set [ns])))))

(defn get-all-dependents [nss]
  (let [topo-sort' (build-topo-sort ns->dependents)]
    (reverse (apply concat (topo-sort' (set nss))))))

#_(prn "dependents" (get-all-dependents [ "example.core" "figwheel.client.file_reloading" "cljs.core"]))

#_(prn "dependencies" (get-all-dependencies "figwheel.client.file_reloading"))

#_(time (get-all-dependents [ "example.core" "figwheel.client.file_reloading" "cljs.core"]))

(defn unprovide! [ns]
  (let [path (name->path ns)]
    (gobj/remove js/goog.dependencies_.visited path)
    (gobj/remove js/goog.dependencies_.written path)
    (gobj/remove js/goog.dependencies_.written (str js/goog.basePath path))))

;; this matches goog behavior in url resolution should actually just
;; use that code
(defn resolve-ns [ns] (str goog/basePath (name->path ns)))

(defn addDependency [path provides requires]
  (doseq [prov provides]
    (path-to-name! path prov)
    (doseq [req requires]
      (name-to-parent! req prov))))

(defn figwheel-require [src reload]
  ;; require is going to be called
  (set! (.-require js/goog) figwheel-require)
  (when (= reload "reload-all")
    (doseq [ns (get-all-dependencies src)] (unprovide! ns)))
  (when reload (unprovide! src))
  (.require_figwheel_backup_ js/goog src))

(defn bootstrap-goog-base
  "Reusable browser REPL bootstrapping. Patches the essential functions
  in goog.base to support re-loading of namespaces after page load."
  []
  ;; The biggest problem here is that clojure.browser.repl might have
  ;; patched this or might patch this afterward
  (when-not js/COMPILED
    ;; 
    (set! (.-require_figwheel_backup_ js/goog) (or js/goog.require__ js/goog.require))
    ;; suppress useless Google Closure error about duplicate provides
    (set! (.-isProvided_ js/goog) (fn [name] false))
    ;; provide cljs.user
    (setup-path->name!)
    (setup-ns->dependents!)

    (set! (.-addDependency_figwheel_backup_ js/goog) js/goog.addDependency)
    (set! (.-addDependency js/goog)
          (fn [& args]
            (apply addDependency args)
            (apply (.-addDependency_figwheel_backup_ js/goog) args)))
    
    (goog/constructNamespace_ "cljs.user")
    ;; we must reuse Closure library dev time dependency management, under namespace
    ;; reload scenarios we simply delete entries from the correct
    ;; private locations
    (set! (.-CLOSURE_IMPORT_SCRIPT goog/global) queued-file-reload)
    (set! (.-require js/goog) figwheel-require)))

(defn patch-goog-base []
  (defonce bootstrapped-cljs (do (bootstrap-goog-base) true)))

(def reload-file*
  (condp = (utils/host-env?)
    :node
    (let [path-parts #(string/split %  #"[/\\]")
          sep (if (re-matches #"win.*" js/process.platform ) "\\" "/")
          root (string/join sep (pop (pop (path-parts js/__dirname))))]
      (fn [request-url callback]
        (dev-assert (string? request-url) (not (nil? callback)))
        (let [cache-path
              (string/join
               sep
               (cons root
                     (path-parts (fix-node-request-url request-url))))]
          (aset (.-cache js/require) cache-path nil)
          (callback (try
                      (js/require cache-path)
                      (catch js/Error e
                        (utils/log :error (str  "Figwheel: Error loading file " cache-path))
                        (utils/log :error (.-stack e))
                        false))))))
    
    :html (fn [request-url callback]
            (dev-assert (string? request-url) (not (nil? callback)))  
            (let [deferred (loader/load (add-cache-buster request-url)
                                        #js { :cleanupWhenDone true })]
              (.addCallback deferred #(apply callback [true]))
              (.addErrback deferred #(apply callback [false]))))
    (fn [a b] (throw "Reload not defined for this platform"))))

(defn reload-file [{:keys [request-url] :as file-msg} callback]
  (dev-assert (string? request-url) (not (nil? callback)))
  (utils/debug-prn (str "FigWheel: Attempting to load " request-url))
  (reload-file* request-url
                (fn [success?]
                  (if success?
                    (do
                      (utils/debug-prn (str "FigWheel: Successfully loaded " request-url))
                      (apply callback [(assoc file-msg :loaded-file true)]))
                    (do
                      (utils/log :error (str  "Figwheel: Error loading file " request-url))
                      (apply callback [file-msg]))))))

;; for goog.require consumption
(defonce reload-chan (chan))

(defonce on-load-callbacks (atom {}))

(defonce dependencies-loaded (atom []))

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
        (if-let [callback (get @on-load-callbacks url)]
          (callback file-msg)
          (swap! dependencies-loaded conj file-msg))
        (recur)))))

(defn queued-file-reload [url] (put! reload-chan url))

(defn require-with-callback [{:keys [namespace] :as file-msg} callback]
  (let [request-url (resolve-ns namespace)]
    (swap! on-load-callbacks assoc request-url
           (fn [file-msg']
             (swap! on-load-callbacks dissoc request-url)
             (apply callback [(merge file-msg (select-keys file-msg' [:loaded-file]))])))
    ;; we are forcing reload here
    (figwheel-require (name namespace) true)))

(defn reload-file? [{:keys [namespace] :as file-msg}]
  (dev-assert (namespace-file-map? file-msg))
  (let [meta-pragmas (get @figwheel-meta-pragmas (name namespace))]
    (and
     (not (:figwheel-no-load meta-pragmas))
     (or
      (:figwheel-always meta-pragmas)
      (:figwheel-load meta-pragmas)
      ;; might want to use .-visited here
      (provided? (name namespace))))))

(defn js-reload [{:keys [request-url namespace] :as file-msg} callback]
  (dev-assert (namespace-file-map? file-msg))
  (if (reload-file? file-msg)
    (require-with-callback file-msg callback)
    (do
      (utils/debug-prn (str "Figwheel: Not trying to load file " request-url))
      (apply callback [file-msg]))))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js-reload
     file-msg
     (fn [url]
       #_(patch-goog-base)
       (put! out url)
       (close! out)))
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


(defn eval-body [{:keys [eval-body file]} opts]
  (when (and eval-body (string? eval-body))
    (let [code eval-body]
      (try
        (utils/debug-prn (str "Evaling file " file))
        (utils/eval-helper code opts)
        (catch :default e
          (utils/log :error (str "Unable to evaluate " file)))))))

(defn expand-files [files]
  (let [deps (get-all-dependents (map :namespace files))]
    (filter (comp not #{"figwheel.connect"} :namespace)
            (map
             (fn [n]
               (if-let [file-msg (first (filter #(= (:namespace %) n) files))]
                 file-msg
                 {:type :namespace :namespace n}))
             deps))))

(defn sort-files [files]
  (if (<= (count files) 1) ;; no need to sort if only one
    files
    (let [keep-files (set (keep :namespace files))]
      (filter (comp keep-files :namespace) (expand-files files)))))

(defn get-figwheel-always []
  (map (fn [[k v]] {:namespace k :type :namespace})
       (filter (fn [[k v]]
                 (:figwheel-always v)) @figwheel-meta-pragmas)))

(defn reload-js-files [{:keys [before-jsload on-jsload reload-dependents] :as opts}
                       {:keys [files figwheel-meta recompile-dependents] :as msg}]
  (when-not (empty? figwheel-meta)
    (reset! figwheel-meta-pragmas figwheel-meta))
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
    (reset! dependencies-loaded (list))
    (let [all-files (filter #(and (:namespace %)
                                  (not (:eval-body %)))
                            files)
          ;; add in figwheel always
          all-files (concat all-files (get-figwheel-always))
          all-files (if (or reload-dependents recompile-dependents)
                      (expand-files all-files)
                      (sort-files all-files))
          ;_       (prn "expand-files" (expand-files all-files))
          ;_       (prn "sort-files" (sort-files all-files))
          res'    (<! (load-all-js-files all-files))
          res     (filter :loaded-file res')
          files-not-loaded  (filter #(not (:loaded-file %)) res')
          dependencies-that-loaded (filter :loaded-file @dependencies-loaded)]
      (when (not-empty dependencies-that-loaded)
        (utils/log :debug "Figwheel: loaded these dependencies")
        (utils/log (pr-str (map (fn [{:keys [request-url]}]
                                  (string/replace request-url goog/basePath ""))
                                (reverse dependencies-that-loaded)))))      
      (when (not-empty res)
        (utils/log :debug "Figwheel: loaded these files")
        (utils/log (pr-str (map (fn [{:keys [namespace file]}]
                                  (if namespace
                                    (name->path (name namespace))
                                    file)) res)))
        (js/setTimeout #(do
                          (on-jsload-custom-event res)
                          (apply on-jsload [res])) 10))
      
      (when (not-empty files-not-loaded)
        (utils/log :debug "Figwheel: NOT loading these files ")
        (let [{:keys [figwheel-no-load not-required]}
              (group-by
               (fn [{:keys [namespace]}]
                 (let [meta-data (get @figwheel-meta-pragmas (name namespace))]
                   (cond
                     (nil? meta-data) :not-required
                     (meta-data :figwheel-no-load) :figwheel-no-load
                     :else :not-required)))
               files-not-loaded)]
          (when (not-empty figwheel-no-load)
            (utils/log (str "figwheel-no-load meta-data: "
                            (pr-str (map (comp name->path :namespace) figwheel-no-load)))))
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

(defn distictify [key seqq]
  (vals (reduce #(assoc %1 (get %2 key) %2) {} seqq)))

(defn reload-css-file [{:keys [file] :as f-data}]
  (when-let [link (get-correct-link f-data)]
    (add-link-to-doc link (clone-link link (.-href link)))
    #_(add-link-to-doc (create-link file))))

(defn reload-css-files [{:keys [on-cssload] :as opts} {:keys [files] :as files-msg}]
  (when (utils/html-env?)
    (doseq [f (distictify :file files)] (reload-css-file f))
    (js/setTimeout #(do
                      (on-cssload-custom-event files)
                      (on-cssload files))
                   100)))
