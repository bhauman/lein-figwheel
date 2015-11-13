(defproject figwheel-sidecar "0.5.0"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel"
        :dir ".."}
  :dependencies
  [[org.clojure/clojure "1.7.0"]
   [org.clojure/clojurescript "1.7.170"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.2.371"]   
   [com.stuartsierra/component "0.3.0"]
   [http-kit "2.1.18"]
   [ring-cors "0.1.7"]
   [compojure "1.4.0"]
   [clj-stacktrace "0.2.8"]
   [digest "1.4.4"]
   [figwheel "0.5.0"]
   [hawk "0.2.5"]])

