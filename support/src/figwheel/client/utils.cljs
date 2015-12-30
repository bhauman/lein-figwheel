(ns ^:figwheel-no-load figwheel.client.utils
    (:require [clojure.string :as string]
              [goog.userAgent.product :as product])
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

(defn async-eval-fn? [eval-fn]
  (boolean (:async (meta eval-fn))))

(defn eval-helper
  "Evaluates javascript code in way specified by figwheel config (opts).
   If there is custom eval-fn defined use it, otherwise call javascript eval on passed code.
   Custom eval-fn can be marked as async. This can be used by Dirac DevTools."
  ([code opts]
   (eval-helper code opts identity))
  ([code opts result-callback]
    (let [{:keys [eval-fn]} opts]
      (if eval-fn
        (if (async-eval-fn? eval-fn)
          (eval-fn code opts result-callback)
          (result-callback (eval-fn code opts)))
        (result-callback (js* "eval(~{code})"))))))

(defn truncate-stack-trace [stack-str]
  (take-while #(not (re-matches #".*eval_javascript_STAR__STAR_.*" %))
              (string/split-lines stack-str)))

(defn get-ua-product []
  (cond
    (node-env?) :chrome
    product/SAFARI :safari
    product/CHROME :chrome
    product/FIREFOX :firefox
    product/IE :ie))

(def base-path (base-url-path))

(defn eval-javascript** [code repl-print-fn opts result-handler]
  (try
    (binding [*print-fn* repl-print-fn
              *print-newline* false]
      (eval-helper code opts (fn [result]
                               (result-handler
                                 {:status     :success,
                                  :ua-product (get-ua-product)
                                  :value      result}))))
    (catch js/Error e
      (result-handler
        {:status     :exception
         :value      (pr-str e)
         :ua-product (get-ua-product)
         :stacktrace (string/join "\n" (truncate-stack-trace (.-stack e)))
         :base-path  base-path}))
    (catch :default e
      (result-handler
        {:status     :exception
         :ua-product (get-ua-product)
         :value      (pr-str e)
         :stacktrace "No stacktrace available."}))))

(defn ensure-cljs-user
  "The REPL can disconnect and reconnect lets ensure cljs.user exists at least."
  []
  ;; this should be included in the REPL
  (when-not js/cljs.user
    (set! js/cljs.user #js {})))
