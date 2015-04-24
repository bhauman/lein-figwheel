(ns example.sss
  #?(:cljs
     (:require-macros
      [example.sss :refer [adder]])))

(defn add [] (+ 1 2))

#?(
:clj
(defmacro adder [a b]
  `(+ ~a ~b 20))
)
