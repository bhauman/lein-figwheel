(ns figwheel.client.utils)

(def ^:dynamic *print-debug* false)

(defn debug-prn [o]
  (when *print-debug*
    (let [o (if (or (map? o)
                  (seq? o))
            (prn-str o)
            o)]
      (.log js/console o))))
