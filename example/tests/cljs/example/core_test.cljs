(ns example.core-test
  (:require
   [example.core :as ex]
   [cljs.test :refer-macros [deftest testing is run-tests]]))

(deftest add-todo-test
  (let [todos (ex/add-todo {:content "buy house"} [{:content "hi"}])]
    (is (= (count todos) 2))
    (is (= (:content (last todos))
           "buy house"))))
