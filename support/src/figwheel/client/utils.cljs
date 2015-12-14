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

(defn eval-helper [code {:keys [eval-fn] :as opts}]
  (if eval-fn
    (eval-fn code opts)
    (js* "eval(~{code})")))

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
      (result-handler
        {:status     :success,
         :ua-product (get-ua-product)
         :value      (eval-helper code opts)}))
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
