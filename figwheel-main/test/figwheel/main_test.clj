(ns figwheel.main-test
  (:require
   [figwheel.main :as fm]
   [figwheel.main.test.utils :refer [with-edn-files with-err-str]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is]]))

(defn main->config [& args]
  (with-redefs [clojure.core/shutdown-agents (fn [])
                figwheel.main/print-conf
                (fn [cnf]
                  (throw (ex-info "stealing config" {::escape-cnf cnf})))]
    (try
      (apply fm/-main (cons "-pc" args))
      (catch clojure.lang.ExceptionInfo e
        (or (::escape-cnf (ex-data e))
            (throw e))))))

(deftest works-when-figwheel-main-is-absent
  ;; basic sanity test
  ;; mainly checking for exceptions being thrown
  (with-edn-files
    {:figwheel-main.edn :delete}
    (is (:options (main->config)))
    (is (:options (main->config "-r")))
    (is (:options (main->config "-m" "figwheel.main")))))

(defn temp-dir? [s]
  ;; works on a mac
  (string/starts-with? s "/var"))

(defn uses-temp-dir? [{:keys [output-to output-dir]}]
  (is (temp-dir? output-to))
  (is (temp-dir? output-dir)))

(deftest noargs-uses-temp-dir-when-not-present-target-dir
  (with-edn-files
    {:figwheel-main.edn {:target-dir "never-gonna-find-me"}
     :scripty-test.cljs "(println (+ 1 2 3))"}
    (uses-temp-dir? (:options (main->config)))
    (uses-temp-dir? (:options (main->config "-r")))
    (uses-temp-dir? (:options (main->config "-m" "figwheel.main")))
    (uses-temp-dir? (:options (main->config "scripty-test.cljs")))
    (uses-temp-dir? (:options (main->config "-")))))

(deftest auto-adds-target-classpath-for-compile
  (with-edn-files
    {:figwheel-main.edn {:target-dir "never-gonna-find-me"}
     :scripty-test.cljs "(println (+ 1 2 3))"}
    (is (string/includes? (with-err-str (main->config "-b" "dev"))
                          "Attempting to dynamically add classpath!!"))
    (is (string/includes? (with-err-str (main->config "-bo" "dev"))
                          "Attempting to dynamically add classpath!!"))
    (is (string/includes? (with-err-str (main->config "-c" "figwheel.main"))
                          "Attempting to dynamically add classpath!!"))))

#_(main-to-print-config "-pc" "-r" )
