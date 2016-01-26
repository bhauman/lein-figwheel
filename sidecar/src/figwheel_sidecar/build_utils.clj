(ns figwheel-sidecar.build-utils
  (:require
   [figwheel-sidecar.utils :as utils]))

(defn add-compiler-env [{:keys [build-options] :as build}]
  (assoc build :compiler-env (utils/compiler-env build-options)))

(defn get-project-builds []
  (into (array-map)
        (map
         (fn [x]
           [(:id x)
            (add-compiler-env x)])
         (:all-builds (fetch-config)))))
