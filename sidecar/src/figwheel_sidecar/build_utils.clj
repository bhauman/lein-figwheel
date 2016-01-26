(ns figwheel-sidecar.build-utils
  (:require
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.utils :as utils]
   [clojure.string :refer [join]]))

(defn add-compiler-env [{:keys [build-options] :as build}]
  (assoc build :compiler-env (utils/compiler-env build-options)))

;; likely useless to call this in current process:
;; really needs to be checked in lein process
#_(defn assert-clojurescript-version []
  (let [cljs-version ((juxt :major :minor :qualifier) cljs.util/*clojurescript-version*)]
    (config/friendly-assert
     (>= (compare cljs-version [1 7 170]) 0)
     (str
      "ClojureScript >= 1.7.170 - Figwheel requires ClojureScript 1.7.170 at least. Current version "
      (join "." cljs-version) " \n"
      "Please make sure you are depending on ClojureScript 1.7.170 at least.\n"
      "Check your dependencies and if using leiningen try: lein deps :tree"))))


