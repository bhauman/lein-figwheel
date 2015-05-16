(ns ^:figwheel-no-load figwheel.client.utils
    (:require [clojure.string :as string])
    (:import [goog]))

;; don't auto reload this file it will mess up the debug printing

(def ^:dynamic *print-debug* false)

(defn html-env? [] (goog/inHtmlDocument_))

(defn node-env? [] (not (nil? goog/nodeGlobalRequire)))

(defn base-url-path [] (string/replace goog/basePath #"(.*)goog/" #(str %2)))

(defn host-env? []
  (cond
    (html-env?) :html
    (node-env?) :node
    :else :html))

(defn dispatch-custom-event [event-name data]
  (when (and (html-env?) (aget js/window "CustomEvent"))
    (.dispatchEvent (.-body js/document)
                    (js/CustomEvent. event-name
                                     (js-obj "detail" data)))))

(defn debug-prn [o]
  (when *print-debug*
    (let [o (if (or (map? o)
                  (seq? o))
            (prn-str o)
            o)]
      (.log js/console o))))

(defn log
  ([x] (log :info x))
  ([level arg]
   (let [f (condp = (if (html-env?) level :info)
            :warn  #(.warn js/console %)
            :debug #(.debug js/console %)
            :error #(.error js/console %)
            #(.log js/console %))]
     (f arg))))
