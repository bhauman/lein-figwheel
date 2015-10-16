(ns example.macros)

(defmacro testmac [body]
  `(str ~body " yep this works now"))
