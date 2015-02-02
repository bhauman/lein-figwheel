(ns ^:figwheel-no-load figwheel.client.utils)

;; don't auto reload this file it will mess up the debug printing

(def ^:dynamic *print-debug* false)

(defn html-env? [] (.inHtmlDocument_ js/goog))

(defn node-env? [] (not (nil? (aget js/goog "nodeGlobalRequire"))))

(defn host-env? []
  (cond
    (html-env?) :html
    (node-env?) :node
    :else :html))

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
