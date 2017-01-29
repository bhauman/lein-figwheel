(ns node-example.core
  (:require [cljs.nodejs :as nodejs]
            [goog.object :as gobj]
            [goog.string :as gstring]
            [clojure.string :as string]))

(nodejs/enable-util-print!)

(println "Hello from the Node!")

(defn helper [] "hey there")

(def -main (fn [] nil))

(set! *main-cli-fn* -main)


