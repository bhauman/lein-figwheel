(ns cljs.repl.figwheel
  (:require
   [figwheel.repl :as fr]))

(defn repl-env [& {:as opts}]
  (fr/repl-env* opts))
