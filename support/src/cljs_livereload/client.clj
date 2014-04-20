(ns cljs-livereload.client
  (:require
   [cljs.compiler :refer (munge)])
  (:refer-clojure :exclude (munge defonce)))

(defmacro defonce
  [vname expr]
  (let [ns (-> &env :ns :name name munge)
        mname (munge (str vname))]
    `(when-not (.hasOwnProperty ~(symbol "js" ns) ~mname)
       (def ~vname ~expr))))
