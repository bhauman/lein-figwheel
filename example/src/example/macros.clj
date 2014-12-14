(ns example.macros
  (:require
   [example.macro-helper :as mh]))

(defmacro log [x]
  `(let [a# ~x]
     (println ~(mh/prefix) ~(pr-str x))
     (.log js/console a#)
     a#))
