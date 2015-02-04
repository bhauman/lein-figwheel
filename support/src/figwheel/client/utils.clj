(ns figwheel.client.utils
  (:require [cljs.analyzer.api :as api]
            [clojure.walk :as walk]))

(def dev-blocks? (atom false))

(defmacro enable-dev-blocks! []
  (reset! dev-blocks? true)
  `(do))

(defmacro disable-dev-blocks! []
  (reset! dev-blocks? false)
  `(do))

(defmacro dev [& body]
  (if @dev-blocks?
    `(do ~@body)
    `(comment ~@body)))

(defmacro dev-assert [& body]
  `(dev
     ~@(map (fn [pred-stmt] `(assert ~pred-stmt)) body)))

(defn no-seqs [b]
  (walk/postwalk #(if (seq? %) (vec %) %) b))

(defmacro get-all-ns-meta-data []
  (no-seqs
   (into {}
         (map (juxt name meta)
              (map :name (map api/find-ns (api/all-ns)))))))

