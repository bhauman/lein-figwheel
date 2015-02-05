(ns figwheel.client.utils
  (:require [cljs.analyzer.api :as api]
            [cljs.compiler]
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
