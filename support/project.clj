(defproject figwheel "0.5.15"
  :description "This project contains the client side code for Figwheel."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel"
        :dir ".."}
  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.9.946"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.4.474"
    :exclusions [org.clojure/tools.reader]]])
