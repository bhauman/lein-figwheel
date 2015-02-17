(ns ^:figwheel-always example.test-runner
  (:require
   [example.core-test]
   [cljs.test :refer-macros [run-tests]]))

(cljs.test/run-tests 'example.core-test)
