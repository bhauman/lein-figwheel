(ns figwheel.main.ansi-party
  (:require [clojure.walk :as walk]))

(def ^:dynamic *use-color* true)
(def ^:dynamic *formatters* {})

(defn format-str [x]
  (if (string? x)
    x
    (walk/postwalk
     (fn [x]
       (cond
         (vector? x)
         (let [[kys args] (split-with keyword? x)
               form-fns (not-empty (keep *formatters* kys))
               form-fn (if form-fns (apply comp form-fns) identity)]
           (form-fn (apply str args)))
         (seq? x) (apply str x)
         :else x))
     x)))

(defn ansi-code [code]
  (when *use-color* (str \u001b code)))

(defn ansi-fn* [code]
  (fn [s] (str (ansi-code code) s (ansi-code "[0m"))))

(defmacro with-added-formatters [formatter-map & body]
  `(binding [*formatters* (merge *formatters* ~formatter-map)]
    ~@body))

(defn- setup-ansi-table [ansi-code-map]
  (->> ansi-code-map
       (map (fn [[k v]] [k (ansi-fn* v)]))
       (into {})))

(def ANSI-CODES
  {:reset              "[0m"
   :bright             "[1m"
   :blink-slow         "[5m"
   :underline          "[4m"
   :underline-off      "[24m"
   :inverse            "[7m"
   :inverse-off        "[27m"
   :strikethrough      "[9m"
   :strikethrough-off  "[29m"

   :default "[39m"
   :white   "[37m"
   :black   "[30m"
   :red     "[31m"
   :green   "[32m"
   :blue    "[34m"
   :yellow  "[33m"
   :magenta "[35m"
   :cyan    "[36m"

   :bg-default "[49m"
   :bg-white   "[47m"
   :bg-black   "[40m"
   :bg-red     "[41m"
   :bg-green   "[42m"
   :bg-blue    "[44m"
   :bg-yellow  "[43m"
   :bg-magenta "[45m"
   :bg-cyan    "[46m"})

(defn ansi-fn [k]
  (ansi-fn* (get ANSI-CODES k)))

(alter-var-root #'*formatters* merge (setup-ansi-table ANSI-CODES))
