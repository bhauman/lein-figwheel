(ns figwheel-sidecar.config-check.document
  (:require
   [figwheel-sidecar.config-check.type-check
    :as tc
    :refer [doc] :as tc]
   [clojure.java.io :as io]
   [clojure.string :as string]))

#_(def compiler-options-lines 
  (string/split-lines (slurp (io/resource "conf-fig-docscompiler_options.txt"))))

(defn keyword-line? [s]
  (and (-> s
           (string/split #"\s+")
           count
           (= 1))
       (.startsWith s ":")))

(defn example-line? [kd l]
  (and (not (nil? l))
       (.startsWith l (str "  " (pr-str (:keyword kd))))))

(defn try-parse [x]
  (try
    (read-string x)
    (catch java.lang.Exception e
      x)))

(defn figure-out-example [ex]
  (if (vector? ex)
    (condp = (first ex)
      :CreateExampleMap    {:example-construct-from ex}
      :CreateExampleVector {:example-construct-from ex}
      {:example ex})
    {:example ex}))

(defn parse-out-example [kd]
  (let [result (split-with
                (partial example-line? kd)
                (keep identity
                      (drop-while string/blank?
                                  (reverse (:content kd)))))]
    (if (->> result ffirst (example-line? kd))
      (let [ex (->> (string/split (ffirst result) #"\s+") (drop 2) (string/join " ")
                    try-parse)]
        (merge kd
               (figure-out-example ex)
               {:content (vec (reverse (second result)))}))
      kd)))

(defn parse-out-docs [lines]
  (->>
   (reduce (fn [lines l] (vec
                         (if (keyword-line? l)
                           (concat [{:keyword (read-string l)}] lines)
                           (update-in lines [0 :content] (comp vec conj) l))))
           []
           lines)
   (mapv parse-out-example)
   reverse
   #_(map #(update-in %
          [:content] 
          (fn [x] (string/join "\n" x))))))

(defn docs-for-type [typ]
  (when-let [doc-resource
             (->> (str typ ".txt")
                  (java.io.File. "conf-fig-docs")
                  str
                  io/resource)]
    (-> doc-resource
        slurp
        string/split-lines
        parse-out-docs)))

(defn ->doc-rules [[typ-doc & docs]]
  (let [name-sym (symbol (name (:keyword typ-doc)))
        typ-doc  (merge typ-doc
                  {:content (string/join "\n" (:content typ-doc))})
        rules    (map #(update-in % [:content] (fn [x] (string/join "\n" x))) docs)
        rules    (into {} (map (juxt :keyword identity) rules))]
    (doc name-sym typ-doc rules)))

(defn get-docs [typs]
  (vec (mapcat ->doc-rules (mapv docs-for-type typs))))

#_ (get-docs
    ['CompilerOptions
     'FigwheelOptions
     'FigwheelClientOptions
     'BuildOptionsMap
     'CljsbuildOptions
     'RootMap
     'ReloadCljFiles])

#_(docs-for-type 'CompilerOptions)
