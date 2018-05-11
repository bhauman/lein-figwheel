(ns figwheel.repl.preload
  (:require [figwheel.repl :as fr]))

(if (= fr/host-env :html)
  (.addEventListener goog.global "load" #(fr/connect))
  (fr/connect))
