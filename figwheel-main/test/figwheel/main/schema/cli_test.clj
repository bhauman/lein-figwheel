(ns figwheel.main.schema.cli-test
  (:require
   [figwheel.main.schema.cli :refer [validate-cli-extra]]
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
        "is being interpreted")

  )


(deftest ignored-args
  (incl (validate-cli-extra ["-c" "figwheel.main" "-s" "asdf:asdf" "asd"])
        "Ignored Extra CLI arguments"
        "extra args are only allowed")
  (incl (validate-cli-extra ["-h" "figwheel.main"])
        "Ignored Extra CLI arguments"
        "extra args are only allowed")

  )

(deftest missing-main-opt
  (incl (validate-cli-extra ["-e" "(list)"])
        "Missing main option"
        "must add a main option for the -e")

  #_(incl (validate-cli-extra ["-e" "(list)"])
        "Missing main option"
        "must add a main option for the -e")
  )
