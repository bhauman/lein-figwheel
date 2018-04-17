(ns figwheel.main
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [figwheel.repl :as fw-repl]
   [figwheel.core :as fw-core]
   [cljs.main :as cm]
   [cljs.cli :as cli]
   [cljs.repl]
   [cljs.env]
   [cljs.build.api :as bapi]
   [hawk.core :as hawk]))

;; TODO
;; builds
;; build config

;; isolate a build by ID for each repl
;; identify a notion of identity(i.e. build-id) at the connection level perhaps a fn

;; better watcher build connection
;; load changed clj files

;; bind connections and verify that it works

;; opening files in editor
;; CSS watching

;; project generation?

;; Watching we want to allow the specification of multiple directories to watch

;; repl-env is assumed to be figwheel

;; ------------------------------------------------------------
;; Watching
;; ------------------------------------------------------------

(def ^:dynamic *watcher* (atom {:watcher nil :watches {}}))

(defn alter-watches [{:keys [watcher watches]} f]
  (when watcher (hawk/stop! watcher))
  (let [watches (f watches)
        watcher (apply hawk/watch! (map vector (vals watches)))]
    {:watcher watcher :watches watches}))

(defn add-watch! [watch-key watch]
  (swap! *watcher* alter-watches #(assoc % watch-key watch)))

(defn remove-watch! [watch-key]
  (swap! *watcher* alter-watches #(dissoc % watch-key)))

(defn reset-watch! []
  (let [{:keys [watcher]} @*watcher*]
    (when watcher (hawk/stop! watcher))
    (reset! *watcher* {})))

(defn throttle [millis f]
  (fn [{:keys [collector] :as ctx} e]
    (let [collector (or collector (atom {}))
          {:keys [collecting? events]} (deref collector)]
      (if collecting?
        (swap! collector update :events (fnil conj []) e)
        (do
          (swap! collector assoc :collecting? true)
          (future (Thread/sleep millis)
                  (let [events (volatile! nil)]
                    (swap! collector
                           #(-> %
                                (assoc :collecting? false)
                                (update :events (fn [evts] (vreset! events evts) nil))))
                    (f (cons e @events))))))
      (assoc ctx :collector collector))))

#_ (reset-watch!)

(defn- watch-opt
  [cfg path]
  (when-not (.exists (io/file path))
    (if (or (string/starts-with? path "-")
            (string/blank? path))
      (throw
        (ex-info
          (str "Missing watch path")
          {:cljs.main/error :invalid-arg}))
      (throw
        (ex-info
          (str "Watch path " path " does not exist")
          {:cljs.main/error :invalid-arg}))))
  (update-in cfg [:options :watch] (fnil conj []) path))

(defn figwheel-opt [cfg bl]
  (assoc-in cfg [::options-meta :figwheel] (not= bl "false")))

(defn build-opt [cfg bn]
  (assoc-in cfg [::options-meta :build-name] )

  )

(def figwheel-commands
  {:init {["-w" "--watch"]
          {:group :cljs.cli/compile :fn watch-opt
           :arg "path"
           :doc "Continuously build, only effective with the --compile main option"}
          ["-fw" "--figwheel"]
          {:group :cljs.cli/compile :fn figwheel-opt
           :arg "bool"
           :doc (str "Use Figwheel to auto reload and report compile info.\n"
                     "Only takes effect when watching is happening and the\n"
                     "optimizations level is :none or nil.\n"
                     "Defaults to true.")}}})

(alter-var-root #'cli/default-commands cli/add-commands
                figwheel-commands)

;; ------------------------------------------------------------
;; CLJS Main Argument manipulation
;; ------------------------------------------------------------

;; let's just insert a processing step at the end of inits only

(defn main-arg? [[arg val]]
  (cli/dispatch? cli/default-commands :main arg))

;; TODO test this thoroughly

(defn split-main-cli [args]
  (if-let [args
           (re-matches
            #"(?i)(-c|--compile)\s(\S+)\s(?:(-r|--repl)|(-s|--serve)\s(\S+))\s(.*)"
            (string/join " " (flatten args)))]
    [(partition-all 2 (filter some? (take 5 (rest args))))
     (string/split (last args) #"\s")]
    (if (#{"-r" "--repl" "-h" "--help" "-?"} (ffirst args)) ;; no arg main flag
      [[[(ffirst args)]]
       (rest (flatten args))]
      (let [[main-arg r] (split-with main-arg? args)]
        [main-arg (flatten r)]))))

#_(split-main-cli (partition-all 2 (string/split "-m a b c"  #"\s")))

#_(cljs.main/-main)
;; TODO need to allow for things that take no args instead of partition 2
(defn split-args [normalized]
  (let [main-arg? #(cli/dispatch? cli/default-commands :main (first %))
        [init-args args] (split-with (complement main-arg?) (partition-all 2 normalized))
        [main-args cli-args] (split-main-cli args)]
    {:init (vec init-args)
     :main (vec main-args)
     :app-main-cli  cli-args}))

(defn assemble-args [{:keys [init main app-main-cli]}]
  (concat (flatten init) (flatten main) app-main-cli))

(defn default-repl-env [{:keys [init] :as args}]
  (if-let [[f v] (first (filter #(#{"-re" "--repl-env"} (first %))
                                init))]
    (cond-> args
      (= v "figwheel")
      (assoc ::figwheel-repl true))
    (-> args
        (update :init #(cons ["-re" "figwheel"] %))
        (assoc ::figwheel-repl true))))

(defn index-of-compiler-options [{:keys [init]}]
  (let [[pre post] (split-with (complement #(#{"-co" "--compile"} (first %)))
                               init)]
    (if (empty? post) nil (count pre))))

#_(index-of-compiler-option (split-args test-args))

(defn get-options [sargs]
  (when-let [idx (index-of-compiler-options sargs)]
    (read-string (second ((vec (:init sargs)) idx)))))

(defn update-options [sargs f]
  (update sargs :init
          (fn [inits]
            (if-let [idx (index-of-compiler-options sargs)]
              (update (vec inits)
                      idx (fn [[flag options]]
                            [flag (pr-str (f (read-string options)))]))
              (vec (cons ["-co" (pr-str (f {}))]
                         inits))))))

#_(update-options (split-args test-args) (fn [options]
                                         (assoc options :hi 1)))

#_(get-options (split-args test-args))

(defn has-arg? [init-or-main-arg-list flag]
  (->> init-or-main-arg-list
       reverse
       (filter #(flag (first %)))
       first))

(defn get-arg [init-or-main-arg-list flag]
  (second (has-arg? init-or-main-arg-list flag)))

(defn figwheel-adjust-options [sargs]
  (update-options
   sargs
   #(update % :preloads
            (fn [p]
              (vec (distinct (concat p '[figwheel.repl.preload figwheel.core])))))))

(defn opt-none? [{:keys [init] :as sargs}]
  (let [opt (first (filter some? [(get-arg init #{"-O" "--optimizations"})
                                 (:optimizations (get-options sargs))]))]
    (or (nil? opt) (#{"none" :none} opt))))

(defn output-to [{:keys [init] :as sargs}]
  (first (filter some? [(get-arg init #{"-o" "--output-to"})
                        (:output-to (get-options sargs))])))

(defn target [{:keys [init] :as sargs}]
  (first (filter some? [(get-arg init #{"-t" "--target"})
                        (:target (get-options sargs))])))

(defn figwheel-mode? [sargs]
  (boolean
   (and (not= "false" (get-arg (:init sargs) #{"-fw" "--figwheel"}))
       (opt-none? sargs)
       (get-arg (:init sargs) #{"-w" "--watch"})
       (get-arg (:main sargs) #{"-c" "--compile"}))))




;; -b build
;; defaults to watch and compile with repl
;; output-to


#_(assemble-args (process-args test-args))

#_(opt-none? (split-args test-args))
#_(figwheel-mode? (split-args test-args))

#_(has-arg? (:init (split-args test-args))
            #{"-re" "--repl-env"})
;; TODO --figwheel true

;; figwheel mode
;; :and
;; - not --figwheel false
;; - build opt is nil or :none
;; - watching is enabled

;; adjustments for figwheel mode



;; figwheel server is started
;; :or
;; - in figwheel-mode
;; - -r and figwheel repl env
;; - -serve

;; when server is started always remove serve

;; where to call figwheel.core/start
;; when figwheel-mode call start with seperate repl-env

(defn start-server? [sargs]
  (or (figwheel-mode? sargs)
      (get-arg (:init sargs) #{"-r" "--repl"})
      (get-arg (:init sargs) #{"-s" "--serve"})))

;; figwheel server gets its port from repl config or serve config, or default
;; if -r arg repl config if --serve arg server config, or default
(defn server-host-port [sargs]
  (let [{:keys [host port] :as res}
        (if-let [host-port (get-arg (:main sargs) #{"-s" "--serve"})]
          (let [[host port] (string/split host-port #":")
                host (if (string/blank? host) nil host)
                port (Integer/parseInt port)]
            {:host host :port port})
          (if (and (has-arg? (:main sargs) #{"-r" "--repl"})
                   (::figwheel-repl sargs))
            (let [host (get-arg (:init sargs) #{"-H" "--host"})
                  port (get-arg (:init sargs) #{"-p" "--port"})]
              (cond-> {}
                host (assoc :host host)
                port (assoc :port (Integer/parseInt port))))))]
    (cond-> {:port figwheel.repl/default-port}
      host (assoc :host host)
      port (assoc :port port))))

(defn- norm [args] (cljs.main/normalize (cli/normalize cli/default-commands args)))

(defn process-args [args]
  (-> args
      norm
      split-args
      default-repl-env
      (#(if (figwheel-mode? %) (assoc % ::figwheel-mode? true) %))
      (#(if (opt-none? %) (assoc % ::opt-none? true) %))
      (#(if (start-server? %) (assoc % ::start-server? true) %))
      (#(if (::figwheel-mode? %) (figwheel-adjust-options %) %))
      (#(if (and (::figwheel-mode? %)
                 (has-arg? (:main %) #{"-r" "--repl"}))
          (update % :init (fn [init] (conj (vec init) ["-e" "(figwheel.core/start-from-repl)"])))
          %))
      (#(if (::start-server? %) (assoc % ::host-port (server-host-port %)) %))
      ))

#_(process-args test-args)

;; TODO
;; -b build flag
;; -bb background-build flag

;; TODO dev only
(def test-args [#_"--wow" #_"1" "-co" "{:aot-cache false :asset-path \"out\" :preloads [figwheel.repl.preload figwheel.core] :closure-defines {figwheel.repl/connect-url \"ws://localhost:[[client-port]]/figwheel-connect\"}}" #_"-re" #_"figwheel" "--port" "9501" "--watch" "src" "--watch" "wow" "-d" "target/public/out" "-o" "target/public/out/mainer.js"  "-e" "(figwheel.core/start-from-repl)" "-c" "exproj.core" "-r" "-s"  "a" "b" "c" "d"])


#_(figwheel-mode? (split-args test-args))

(defn file-suffix [file]
  (last (string/split (.getName (io/file file)) #"\.")))

(defn suffix-filter [suffixes]
  (fn [_ {:keys [file]}]
    (and file
         (.isFile file)
         (not (.isHidden file))
         (suffixes (file-suffix file))
         (not (#{\. \#} (first (.getName file)))))))

#_((suffix-filter #{"clj"}) nil {:file (io/file "project.clj")})

(defn watch [inputs opts cenv]
  (prn "Called Watch!!!")
  (add-watch! (-> opts meta :build-id (or :dev))
              {:paths (if (coll? inputs) inputs [inputs])
               :filter (suffix-filter #{"cljs" "clj" "cljc"})
               :handler (throttle
                         50
                         (fn [evts]
                           (let [inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                             (figwheel.core/build inputs opts cenv))))}))

(def default-output-dir (.getPath (io/file "target" "public" "out")))
(def default-output-to  (.getPath (io/file "target" "public" "out" "main.js")))

(defn default-compile
  [repl-env {:keys [ns args options ::options-meta] :as cfg}]
  (println "Default compile")
  (let [rfs      #{"-r" "--repl"}
        sfs      #{"-s" "--serve"}
        env-opts (cljs.repl/repl-options (repl-env))
        repl?    (boolean (or (rfs ns) (rfs (first args))))
        serve?   (boolean (or (sfs ns) (sfs (first args))))
        main-ns  (if (and ns (not ((into rfs sfs) ns)))
                   (symbol ns)
                   (:main options))
        figwheel-mode? (and (:figwheel options-meta true)
                            (:watch opts)
                            (= :none (:optimizations opts :none)))
        opts     (as->
                   (merge
                     #_(select-keys env-opts
                                    (cond-> [:target] repl? (conj :browser-repl)))
                     options
                     (when main-ns
                       {:main main-ns})) opts
                   (cond-> opts
                     (not (:output-to opts))
                     (assoc :output-to default-output-to)
                     (= :advanced (:optimizations opts))
                     (dissoc :browser-repl)
                     (not (:output-dir opts))
                     (assoc :output-dir default-output-dir)
                     figwheel-mode?
                     (update :preloads
                             (fn [p]
                               (vec (distinct
                                     (concat p '[figwheel.repl.preload figwheel.core])))))
                     #_(not (contains? opts :aot-cache))
                     #_(assoc :aot-cache true)))
        convey   (into [:output-dir] cljs.repl/known-repl-opts)
        cfg      (update cfg :options merge (select-keys opts convey))
        cfg      (update cfg :options dissoc :watch)
        source   (when (and (= :none (:optimizations opts :none)) main-ns)
                   (:uri (bapi/ns->location main-ns)))
        cenv     (cljs.env/default-compiler-env)]
    (cljs.env/with-compiler-env cenv
      [opts options-meta]
      #_(if-let [path (:watch opts)]
        (do
          (bapi/build (if (coll? path)
                        (apply bapi/inputs path)
                        path)
                      (dissoc opts :watch)
                      cenv)
          (watch path
                 (dissoc opts :watch)
                 cenv))
        (bapi/build source opts cenv))
      #_(when repl?
        (#'cli/repl-opt repl-env args cfg))
      #_(when serve?
        (#'cli/serve-opt repl-env args cfg)))))

#_(merge (select-keys repl-env [:port
                                :host
                                :output-to
                                :ring-handler
                                :ring-server
                                :ring-server-options
                                :ring-stack
                                :ring-stack-options])
         (select-keys opts [:target
                            :output-to]))

(def server (atom nil))

;; TODO figwheel.core start when not --repl
(defn -main [& args]
  (let [sargs (process-args args)
        forward-args (assemble-args sargs)]
    (clojure.pprint/pprint forward-args)
    (with-redefs [cljs.cli/default-compile default-compile]
      (apply cljs.main/-main forward-args))))

(def args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "--figwheel" "false"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(apply -main args)
#_(.stop @server)
