(ns ^:figwheel-no-load figwheel.client.utils
  (:require [clojure.string :as string]
            [goog.userAgent.product :as product])
    (:import [goog]))

;; don't auto reload this file it will mess up the debug printing

(def ^:dynamic *print-debug* false)

(defn html-env? [] (not (nil? goog/global.document)))

(defn node-env? [] (not (nil? goog/nodeGlobalRequire)))

(defn worker-env? [] (and
                      (nil? goog/global.document)
                      (not (nil? (.-importScripts js/self)))))

(defn host-env? [] (cond (node-env?)   :node
                         (html-env?)   :html
                         (worker-env?) :worker))

(defn base-url-path [] (string/replace goog/basePath #"(.*)goog/" "$1"))

;; Custom Event must exist before calling this
(defn create-custom-event [event-name data]
  (if-not product/IE
    (js/CustomEvent. event-name (js-obj "detail" data))
    ;; in windows world
    ;; this will probably not work at some point in
    ;; newer versions of IE
    (let [event (js/document.createEvent "CustomEvent")]
      (.. event (initCustomEvent event-name false false data))
      event)))

;; actually we should probably lift the event system here off the DOM
;; so that we work well in Node and other environments
(defn dispatch-custom-event [event-name data]
  (when (and (html-env?) (aget js/window "CustomEvent") (js* "typeof document !== 'undefined'"))
    (.dispatchEvent (.-body js/document)
                    (create-custom-event event-name data))))

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
