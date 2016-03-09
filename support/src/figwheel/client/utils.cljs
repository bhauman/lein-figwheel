(ns ^:figwheel-no-load figwheel.client.utils
    (:require [clojure.string :as string])
    (:import [goog]))

;; don't auto reload this file it will mess up the debug printing

(def ^:dynamic *print-debug* false)

(defn html-env? [] (goog/inHtmlDocument_))

(defn node-env? [] (not (nil? goog/nodeGlobalRequire)))

(defn host-env? [] (if (node-env?) :node :html))

(defn base-url-path [] (string/replace goog/basePath #"(.*)goog/" "$1"))

;; actually we should probably lift the event system here off the DOM
;; so that we work well in Node and other environments
(defn dispatch-custom-event [event-name data]
  (when (and (html-env?) (aget js/window "CustomEvent") (js* "typeof document !== 'undefined'"))
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

(defn eval-helper [code {:keys [eval-fn] :as opts}]
  (if eval-fn
    (eval-fn code opts)
    (js* "eval(~{code})")))
