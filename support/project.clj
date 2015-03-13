(defproject figwheel "0.2.6-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel" }
  :dependencies
  [[org.clojure/clojure "1.6.0"]
   [org.clojure/clojurescript "0.0-2850"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.1.346.0-17112a-alpha"]])
