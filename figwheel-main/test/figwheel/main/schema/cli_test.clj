(ns figwheel.main.schema.cli-test
  (:require
   [figwheel.main.schema.cli :refer [validate-cli-extra]]
   [figwheel.main.test.utils :refer [with-edn-files]]
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is]]))

(defmacro incl [expr & ss]
  `(let [exp# ~expr]
     (doseq [s# ~(vec ss)]
       (is (string/includes? exp# s#)))))

(deftest misspelling-args-test
  (testing "similar short flags"
    (incl (validate-cli-extra ["-cc"])
          "Misspelled CLI flag"
          "should probably be: \"-co\""))
  (testing "single dash for double dash"
    (incl (validate-cli-extra ["-print-confi"])
          "Misspelled CLI flag"
          "should probably be: \"--print-config\""))
  (testing "double dash for single dash"
    (incl (validate-cli-extra ["--pc"])
          "Misspelled CLI flag"
          "should probably be: \"-pc"))
  (testing "similar long args"
    (incl (validate-cli-extra ["--ouput-dir"])
          "Misspelled CLI flag"
          "should probably be: \"--output-dir\""
          "Doc for -d --output-dir"
          "Set the output directory")))

(deftest unknown-flag-test
  (incl (validate-cli-extra ["-vxxxxxx"])
        "Unknown CLI flag"
        "-vxxxxxx"
        "^^^^^^^^^^"
        "should be a known CLI flag"))

(deftest unknown-script-test
  (incl (validate-cli-extra ["-O" "advanced" "dev" "-r"])
        "^^^^^"
        "is being interpreted")
  (incl (validate-cli-extra ["dev" "-r"])
        "^^^^^"
        "is being interpreted")
  (incl (validate-cli-extra ["-O" "advanced" "dev"])
        "^^^^^"
        "is being interpreted")
  (incl (validate-cli-extra ["dev"])
        "^^^^^"
        "is being interpreted"))


(deftest ignored-args
  (incl (validate-cli-extra ["-c" "figwheel.main" "-s" "asdf:asdf" "asd"])
        "Ignored Extra CLI arguments"
        "extra args are only allowed")
  (incl (validate-cli-extra ["-h" "figwheel.main"])
        "Ignored Extra CLI arguments"
        "extra args are only allowed"))

(deftest missing-main-opt
  (incl (validate-cli-extra ["-e" "(list)"])
        "Missing main option"
        "must add a main option for the -e")

  (with-edn-files
    {:ex-script.cljs "(list)"}
    (incl (validate-cli-extra ["-i" "ex-script.cljs"])
          "Missing main option"
          "must add a main option for the -i"))

  (incl (validate-cli-extra ["-e" "(list)" "-c" "figwheel.main"])
        "Missing main option"
        "must add a main option for the -e"
        ;; narrows the suggestion
        "\"--repl\", \"-r\"\n")

  (incl (validate-cli-extra ["-O" "advanced"])
        "Missing main option"
        "must add a main option for the -O"
        "--compile"))

(deftest imcompatible-flag-for-main-opt
  (incl (validate-cli-extra ["-e" "(list)" "-s" "asdf:hasdf"])
        "Incompatible flag for main options:  -s"
        "--repl")


  (incl (validate-cli-extra ["-O" "advanced" "-r"])
        "Incompatible flag for main options:  -r"
        "should have the correct main option for the -O"
        "--compile"))

(deftest missing-build-file
  (with-edn-files {:downtown.cljs.edn '{:main downtown.core}
                   :clowntown.cljs.edn '{:main clowntown.core}}
    (incl (validate-cli-extra ["-b" "decent"])
          "Build file decent.cljs.edn not found"
          "should refer to an existing build"
          "\"downtown\"" "\"clowntown\"")
    )

  (with-edn-files {:downtown.cljs.edn '{:main downtown.core}
                   :clowntown.cljs.edn '{:main clowntown.core}}
    (incl (validate-cli-extra ["-bo" "decent" ])
          "Build file decent.cljs.edn not found"
          "should refer to an existing build"
          "\"downtown\"" "\"clowntown\"")
    )

  (with-edn-files {:downtown.cljs.edn '{:main downtown.core}
                   :clowntown.cljs.edn '{:main clowntown.core}}
    (incl (validate-cli-extra ["-bb" "decent" ])
          "Build file decent.cljs.edn not found"
          "should refer to an existing build"
          "\"downtown\"" "\"clowntown\"")
    )

  (with-edn-files {:devver.cljs.edn :delete
                   :helper.cljs.edn :delete
                   :dev.cljs.edn    :delete}
    (incl (validate-cli-extra ["-bb" "decent" ])
          "Build file decent.cljs.edn not found"
          "there are no build files in the current directory")))
