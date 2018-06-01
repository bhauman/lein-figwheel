(ns figwheel.main-test
  (:require
   [figwheel.main :as fm]
   [clojure.test :refer [deftest testing is]]))

(defn main-to-print-config [& args]
  (with-redefs [clojure.core/shutdown-agents (fn [])
                figwheel.main/print-conf
                (fn [cnf]
                  (throw (ex-info "stealing config" {::escape-cnf cnf})))]
    (try
      (apply fm/-main args)
      (catch clojure.lang.ExceptionInfo e
        (or (::escape-cnf (ex-data e))
            (throw e))))))

#_(main-to-print-config "-pc" "-c" "figwheel.main")
