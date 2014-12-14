(ns example.other-macros)

(defmacro logger [x]
  `(do
     (.log js/console ~(pr-str x))
     ~x))

