(ns ^:figwheel-load figwheel.core
  (:require
   #?@(:cljs
       [[figwheel.client.utils :as utils :refer-macros [dev-assert]]
        [figwheel.client.heads-up :as heads-up]
        [goog.object :as gobj]])
   [clojure.set :refer [difference]]
   [clojure.string :as string]
   #?@(:clj
       [[cljs.env :as env]
        [cljs.compiler]
        [cljs.repl]
        [clojure.data.json :as json]]))
  (:import #?@(:cljs [[goog]
                      [goog.async Deferred]
                      [goog Promise]
                      [goog.events EventTarget Event]])))

#?(:cljs
   (do

;; --------------------------------------------------
;; Cross Platform event dispatch
;; --------------------------------------------------
(def event-target (if (and (exists? js/document)
                           (exists? js/document.body))
                    js/document.body
                    (EventTarget.)))

(defonce listener-key-map (atom {}))

(defn unlisten [ky event-name]
  (when-let [f (get @listener-key-map ky)]
    (.removeEventListener event-target (name event-name) f)))

(defn listen [ky event-name f]
  (unlisten ky event-name)
  (.addEventListener event-target (name event-name) f)
  (swap! listener-key-map assoc ky f))

(defn dispatch-event [event-name data]
  (.dispatchEvent
   event-target
   (doto (if (instance? EventTarget event-target)
           (Event. (name event-name) event-target)
           (js/Event. (name event-name) event-target))
     (gobj/add "data" (or data {})))))

(defn event-data [e]
  (gobj/get
   (if-let [e (.-event_ e)] e e)
   "data"))

;; ------------------------------------------------------------
;; Global state
;; ------------------------------------------------------------

(def config-defaults
  {;; this could also be done server side
   :load-warninged-code false
   ::reload-state {}})

(defonce state (atom config-defaults))

;; ------------------------------------------------------------
;; Heads up display logic
;; ------------------------------------------------------------

(let [last-reload-timestamp (atom 0)
      promise-chain (Promise. (fn [r _] (r true)))]
  (defn render-watcher [_ _ o n]
    ;; a new reload has arrived
    (if-let [ts (when-let [ts (get-in n [::reload-state :reload-started])]
                  (and (< @last-reload-timestamp ts) ts))]
      (do (reset! last-reload-timestamp ts)
          (.then promise-chain (fn [] (heads-up/flash-loaded)))))))

(add-watch state ::render-watcher render-watcher)

;; ------------------------------------------------------------
;; Namespace reloading
;; ------------------------------------------------------------

(defonce figwheel-meta-pragmas (atom {}))

(defn immutable-ns? [name]
  (or (#{"goog" "cljs.core" "cljs.nodejs"
         "figwheel.preload"
         "figwheel.connect"} name)
      (goog.string/startsWith "clojure." name)
      (goog.string/startsWith "goog." name)))

(defn name->path [ns]
  (gobj/get js/goog.dependencies_.nameToPath ns))

(defn provided? [ns]
  (gobj/get js/goog.dependencies_.written (name->path ns)))

(defn figwheel-no-load? [{:keys [namespace] :as file-msg}]
  (let [meta-pragmas (get @figwheel-meta-pragmas (name namespace))]
    (:figwheel-no-load meta-pragmas)))

(defn ns-exists? [namespace]
  (some? (reduce (fnil gobj/get #js{})
                 goog.global (string/split (name namespace) "."))))

(defn reload-file? [{:keys [namespace] :as file-msg}]
  (let [meta-pragmas (get @figwheel-meta-pragmas (name namespace))]
    (and
     (not (immutable-ns? namespace))
     (not (figwheel-no-load? file-msg))
     (or
      (:figwheel-always meta-pragmas)
      (:figwheel-load meta-pragmas)
      ;; might want to use .-visited here
      (provided? (name namespace))
      (ns-exists? namespace)))))

;; get rid of extra code in file reloading
;; fix reload all in file reloading

;; use goog logging and make log configurable log level
;; make reloading conditional on warnings
(defn ^:export reload-namespaces [namespaces figwheel-meta]
  (let [figwheel-meta (into {}
                            (map (fn [[k v]] [(name k) v]))
                            (js->clj figwheel-meta :keywordize-keys true))]
    (when-not (empty? figwheel-meta)
      (reset! figwheel-meta-pragmas figwheel-meta)))
  (swap! state assoc-in [::reload-state :reload-started] (.getTime (js/Date.)))
  (when-not (empty? namespaces)
    (js/setTimeout #(dispatch-event :figwheel.before-js-reload {:namespaces namespaces}) 0))
  (let [to-reload (filter #(reload-file? {:namespace %}) namespaces)]
    (doseq [ns to-reload]
      ;; goog/require has to be patched by a repl bootstrap
      (goog/require (name ns) true))
    (let [after-reload-fn
          (fn []
            (when (not-empty to-reload)
              (utils/log (str "Figwheel: loaded " (pr-str to-reload))))
            (when-let [not-loaded (not-empty (filter (complement (set to-reload)) namespaces))]
              (utils/log (str "Figwheel: did not load " (pr-str not-loaded))))
            (dispatch-event :figwheel.js-reload {:reloaded-namespaces to-reload})
            (swap! state assoc ::reload-state {}))]
      (if (and (exists? js/figwheel.client)
               (exists? js/figwheel.client.file_reloading)
               (exists? js/figwheel.client.file_reloading.after_reloads))
        (js/figwheel.client.file_reloading.after_reloads after-reload-fn)
        (js/setTimeout after-reload-fn 100)))))


))





#?(:clj
   (do

(defonce last-compiler-env (atom {}))

(defn client-eval [code]
  (when-not (string/blank? code)
    (cljs.repl/-evaluate
     cljs.repl/*repl-env*
     "<cljs repl>" 1
     code)))

(defn find-figwheel-meta []
  (into {}
        (comp
         (map :ns)
         (map (juxt
               cljs.compiler/munge
               #(select-keys
                 (meta %)
                 [:figwheel-always :figwheel-load :figwheel-no-load])))
         (filter (comp not-empty second)))
        (:sources @env/*compiler*)))

(defn in-upper-level? [topo-state current-depth dep]
  (some (fn [[_ v]] (and v (v dep)))
        (filter (fn [[k v]] (> k current-depth)) topo-state)))

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
                 (when (and dep (not (in-upper-level? @state depth dep)))
                   (topo-sort-helper* dep (inc depth) state)))
               (when (= depth 0)
                 (elim-dups* (reverse (vals @state))))))
            (elim-dups* [[x & xs]]
              (if (nil? x)
                (list)
                (cons x (elim-dups* (map #(clojure.set/difference % x) xs)))))]
      topo-sort*)))

(defn invert-deps [sources]
  (apply merge-with concat
         {}
         (map (fn [{:keys [requires ns]}]
                (reduce #(assoc %1 %2 [ns]) {} requires))
              sources)))

(defn expand-to-dependents [sources deps]
  (reverse (apply concat
                  ((build-topo-sort (invert-deps sources))
                   deps))))

(defn sources-with-paths [files sources]
  (let [files (set files)]
    (filter
     #(when-let [source-file (:source-file %)]
        (files (.getCanonicalPath source-file)))
     sources)))

(defn require-map [env]
  (->> env
       :sources
       (map (juxt :ns :requires))
       (into {})))

(defn changed-dependency-tree? [previous-compiler-env compiler-env]
  (not= (require-map previous-compiler-env) (require-map compiler-env)))

#_(defn- jsonify-string-vector [v]
  (clojure.walk/postwalk (fn [x]
                           (cond
                             (vector? x)
                             (str "[" (string/join "," x) "]")
                             (and (string? x)
                                  (not (.startsWith x "[")))
                             (pr-str x)
                             :else x))
                         v))

(defrecord FakeReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts])
  (-evaluate [_ _ _ js] js)
  (-load [this ns url])
  (-tear-down [_] true))

;; this is a hack for now, easy enough to write this without the hack
(let [noop-repl-env (FakeReplEnv.)]
  (defn add-dependiencies-js [ns-sym output-dir]
    (cljs.repl/load-namespace noop-repl-env ns-sym {:output-dir (or output-dir "out")})))

(defn all-add-dependencies [ns-syms output-dir]
  (string/join "\n"
   (distinct
    (mapcat #(filter
              (complement string/blank?)
              (string/split-lines
               (add-dependiencies-js % output-dir)))
            ns-syms))))

(defn output-dir []
  (-> @env/*compiler* :options :output-dir (or "out")))

(defn root-namespaces [env]
  (clojure.set/difference (->> env :sources (mapv :ns) (into #{}))
                          (->> env :sources (map :requires) (reduce into #{}))))

(defn all-dependency-code [ns-syms]
  (when-let [last-env (get @last-compiler-env env/*compiler*)]
    (when (changed-dependency-tree? last-env @env/*compiler*)
      (let [roots (root-namespaces @env/*compiler*)]
        (all-add-dependencies roots (output-dir))))))

(defn reload-namespace-code [ns-syms]
  (let [figwheel-ns-meta (find-figwheel-meta)
        ns-syms (expand-to-dependents
                 (:sources @env/*compiler*)
                 (concat ns-syms
                         ;; add in figwheel-always
                         (keep (fn [[k v]] (when (:figwheel-always v) k))
                               figwheel-ns-meta)))]
    (str (all-dependency-code ns-syms)
         (format "figwheel.core.reload_namespaces([%s],%s)"
                 (string/join "," (map (comp pr-str str) (mapv cljs.compiler/munge ns-syms)))
                 (json/write-str figwheel-ns-meta)))))

;; TODO do a reload namespace that relies on *repl-env*
;; this is temporary
(defn reload-namespace-code! [ns-syms]
  (let [ret (reload-namespace-code ns-syms)]
    (swap! last-compiler-env assoc env/*compiler* @env/*compiler*)
    ret))

#_(defn reload-namespaces! [ns-syms]
  (when (not-empty ns-syms)
    (client-eval (reload-namespace-code ns-syms))))

(comment
  (swap! scratch assoc :require-map2 (require-map (first (vals @last-compiler-env))))

  (= (-> @scratch :require-map)
     (-> @scratch :require-map2)
     )

  (binding [env/*compiler* (atom (first (vals @last-compiler-env)))]
    #_(add-dependiencies-js 'example.core (output-dir))
    #_(all-add-dependencies '[example.core figwheel.preload]
                          (output-dir))
    #_(reload-namespace-code '[example.core])
    (find-figwheel-meta)
    )

  #_(require 'cljs.core)

  (count @last-compiler-env)
  (map :requires (:sources (first (vals @last-compiler-env))))
  (expand-to-dependents (:sources (first (vals @last-compiler-env))) '[example.fun-tester])

  (def scratch (atom {}))
  (def comp-env (atom nil))
  (keys @comp-env)
  (first (:files @scratch))
  (.getAbsolutePath (:source-file (first (:sources @comp-env))))
  (sources-with-paths (:files @scratch) (:sources @comp-env))
  (invert-deps (:sources @comp-env))
  (expand-to-dependents (:sources @comp-env) '[figwheel.client.utils])
  (clojure.java.shell/sh "touch" "cljs_src/figwheel_helper/core.cljs")
  )


)






   )
