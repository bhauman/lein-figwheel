(ns figwheel.main.schema.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.spec.alpha :as s]
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

;; ------------------------------------------------------------
;; Validate
;; ------------------------------------------------------------

#_(exp/expound ::edn {:watch-dirs ["src"]
                      :ring-handler "asdfasdf/asdfasdf"
                      :reload-clj-files [:cljss :clj]})

#_(s/valid? ::edn {:watch-dirs ["src"]
                   :ring-handler "asdfasdf/asdfasdf"
                   :reload-clj-files [:cljss :clj]})

(defn expound-string [spec form]
  (when-let [explain-data (s/explain-data spec form)]
    (with-out-str
      ((exp/custom-printer
        {:print-specs? false})
       explain-data))))

(defn validate-config! [spec config-data context-msg]
  (if-let [explained (expound-string spec config-data)]
    (throw (ex-info (str context-msg "\n" explained)
                    {::error explained}))
    true))

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
  (let [{:keys [common un-common]}  (group-by :group (sort-by :position (vals @*spec-meta*)))]
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
