(ns figwheel.main.schema.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.spec.alpha :as s]
   [clojure.set]
   [figwheel.main.util :as util]
   [expound.printer :as printer]
   [expound.alpha :as exp]))

(def ^:dynamic *spec-meta* (atom {}))

(defn def-spec-meta [k & args]
  (assert (even? (count args)))
  (swap! *spec-meta* assoc k (assoc (into {} (map vec (partition 2 args)))
                                    :position (count (keys @*spec-meta*))
                                    :key k)))

(defn spec-doc [k doc] (swap! *spec-meta* assoc-in [k :doc] doc))

(defn file-exists? [s] (and s (.isFile (io/file s))))
(defn directory-exists? [s] (and s (.isDirectory (io/file s))))
(defn non-blank-string? [x] (and (string? x) (not (string/blank? x))))

(defn integer-like? [x]
  (and (string? x)
       (re-matches #"\d+" x)))

(defn flag-arg? [s]
  (and (non-blank-string? s)
       (.startsWith s "-")))

(defn not-flag? [x]
  (and (string? x)
       (not (flag-arg? x))))

(defn unquoted-symbol? [a]
  (and (symbol? a)
       (not (string/starts-with? (str a) "'"))))

;; ------------------------------------------------------------
;; Shared specs
;; ------------------------------------------------------------

(exp/def ::host not-flag?
  "should be an existing host interface (i.e. \"localhost\" \"127.0.0.1\" \"0.0.0.0\" \"192.168.0.1\")")

(exp/def ::port integer-like?
  "should be an integer port i.e 9500")

(exp/def ::integer-port integer?
  "should be an integer port i.e 9500")

(defn has-cljs-source-files? [dir]
  (not-empty (clojure.set/intersection
               #{"cljs" "cljc"}
               (util/source-file-types-in-dir dir))))

(exp/def ::has-cljs-source-files has-cljs-source-files?
  "directory should contain cljs or cljc source files")

(exp/def ::unquoted-symbol unquoted-symbol?
  "should be a symbol WITHOUT an initial quote. Quoted symbols are not needed in EDN")

(exp/def ::has-cljs-source-files has-cljs-source-files?
  "directory should contain cljs or cljc source files")


;; ------------------------------------------------------------
;; Validate
;; ------------------------------------------------------------

(defn key-meta-for-problem [{:keys [via :spell-spec.alpha/likely-misspelling-of] :as prob}]
  (or
   (when-let [n (first likely-misspelling-of)]
     (when-let [ns (namespace (first via))]
       (get @*spec-meta* (keyword ns (name n)))))
   (some->> (reverse via)
            (filter @*spec-meta*)
            ;; don't show the root docs
            (filter (complement
                     #{:figwheel.main.schema.config/edn
                       :figwheel.main.schema.cljs-options/cljs-options}))
            first
            (get @*spec-meta*))))

(let [expected-str (deref #'exp/expected-str)]
  (defn expected-str-with-doc [_type spec-name val path problems opts]
    (str (expected-str _type spec-name val path problems opts)
         (when-let [{:keys [key doc]} (key-meta-for-problem (first problems))]
           (when doc
             (str
              "\n\n-- Doc for " (pr-str (keyword (name key)))  " -----\n\n"
             (printer/indent doc)))))))

(defn expound-string [spec form]
  (when-let [explain-data (s/explain-data spec form)]
    (with-redefs [exp/expected-str expected-str-with-doc]
      (with-out-str
        ((exp/custom-printer
          {:print-specs? false})
         explain-data)))))

(defn validate-config! [spec config-data context-msg]
  (if-let [explained (expound-string spec config-data)]
    (throw (ex-info (str context-msg "\n" explained)
                    {::error explained}))
    true))

#_(expound-string
   :figwheel.main.schema.config/edn
   (read-string (slurp "figwheel-main.edn")))

;; ------------------------------------------------------------
;; Spec validation
;; ------------------------------------------------------------

(defmacro ensure-all-registered-keys-included [ignore-reg-keys key-spec]
  (let [spec-keys (set (concat ignore-reg-keys
                               (mapcat second (partition 2 (rest key-spec)))))
        reg-keys  (set (filter #(= (str *ns*) (namespace %))
                               (keys (s/registry))))
        missing-keys (clojure.set/difference reg-keys spec-keys)]
    (assert (empty? missing-keys) (str "missing keys " (pr-str missing-keys))))
  key-spec)

;; ------------------------------------------------------------
;; Generate docs
;; ------------------------------------------------------------

(defn markdown-option-docs [key-datas]
  (string/join
   "\n\n"
   (mapv (fn [{:keys [key doc]}]
           (let [k (keyword (name key))]
             (format "## %s\n\n%s" (pr-str k) doc)))
         key-datas)))

(defn markdown-docs []
  (let [{:keys [common un-common]} (->> (vals @*spec-meta*)
                                        (filter #(-> %
                                                     :key
                                                     namespace
                                                     (= "figwheel.main.schema.config")))
                                        (sort-by :position)
                                        (group-by :group))]
    (str "# Figwheel Main Configuration Options\n\n"
         "The following options can be supplied to `figwheel.main` via the `figwheel-main.edn` file.\n\n"
         "# Commonly used options (in order of importance)\n\n"
         (markdown-option-docs common)
         "\n\n"
         "# Rarely used options\n\n"
         (markdown-option-docs un-common))))

(defn output-docs [output-to]
  (require 'figwheel.main.schema.config)
  (require 'figwheel.server.ring)
  (.mkdirs (.getParentFile (io/file output-to)))
  (spit output-to (markdown-docs)))


#_(validate-config! :figwheel.main.schema.config/edn (read-string (slurp "figwheel-main.edn")) "")

#_(output-docs "doc/figwheel-main-options.md")

#_(markdown-docs)
