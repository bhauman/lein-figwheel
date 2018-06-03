(ns figwheel.main-test
  (:require
   [figwheel.main :as fm]
   [figwheel.main.test.utils :refer [with-edn-files with-err-str logging-fixture]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is use-fixtures]]))

(use-fixtures :once logging-fixture)

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

;; FIX logging output capture
(deftest auto-adds-target-classpath-for-compile
  (with-edn-files
    {:figwheel-main.edn {:target-dir "never-gonna-find-me"}}
    (is (string/includes? (with-out-str (main->config "-b" "dev"))
                          "Attempting to dynamically add classpath!!"))
    (is (string/includes? (with-out-str (main->config "-bo" "dev"))
                          "Attempting to dynamically add classpath!!"))
    (is (string/includes? (with-out-str (main->config "-c" "figwheel.main"))
                          "Attempting to dynamically add classpath!!"))))

(deftest validates-command-line
  (testing "without figwheel-main.edn"
    (with-edn-files
      {:figwheel-main.edn {:target-dir "never-gonna-find-me"}}
      (string/includes? (with-err-str (main->config "-O" "dev")) "should be one of")))
  (testing "with figwheel-main.edn"
    (with-edn-files
      {:figwheel-main.edn :delete}
      (string/includes? (with-err-str (main->config "-O" "dev")) "should be one of"))))

(defn asset-path-relative? [{:keys [options]}]
  (let [{:keys [output-dir asset-path]} options]
    (and output-dir
         asset-path
         (string/ends-with? output-dir asset-path))))

(deftest asset-path-is-relative-to-output-dir
  (with-edn-files
    {:figwheel-main.edn {:target-dir "never-gonna-find-me"}}
    (is (asset-path-relative? (main->config "-co" "dev.cljs.edn" "-r")))
    (is (asset-path-relative? (main->config "-m" "figwheel.main")))
    (is (asset-path-relative? (main->config "-c" "figwheel.main" "-r")))
    (is (asset-path-relative? (main->config "-b" "dev" "-r")))
    (is (asset-path-relative? (main->config "-bo" "dev"))))
  (with-edn-files
    {:figwheel-main.edn :delete}
    (is (asset-path-relative? (main->config "-co" "dev.cljs.edn" "-r")))
    (is (asset-path-relative? (main->config "-m" "figwheel.main")))
    (is (asset-path-relative? (main->config "-c" "figwheel.main" "-r")))
    (is (asset-path-relative? (main->config "-b" "dev" "-r")))
    (is (asset-path-relative? (main->config "-bo" "dev"))))


  )

(deftest dont-infer-watch-directory-for-compile-without-repl-or-serve
  (with-edn-files
    {:dev.cljs.edn '{:main exproj.core}}
    (is (empty? (-> (main->config "-co" "dev.cljs.edn" "-c")
                    :figwheel.main/config :watch-dirs)))
    (is (empty? (-> (main->config "-c" "exproj.core")
                    :figwheel.main/config :watch-dirs)))

    (is (not-empty (-> (main->config "-co" "dev.cljs.edn" "-c" "-r")
                       :figwheel.main/config :watch-dirs)))

    (is (not-empty (-> (main->config "-co" "dev.cljs.edn" "-c" "-s")
                       :figwheel.main/config :watch-dirs)))



    )

  )




#_(main-to-print-config "-pc" "-r" )
