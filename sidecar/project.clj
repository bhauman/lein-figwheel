(defproject figwheel-sidecar "0.2.3-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel" }
  :dependencies
  [[org.clojure/clojure "1.6.0"]
   [org.clojure/clojurescript "0.0-2665"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.1.346.0-17112a-alpha"]
   [com.cemerick/pomegranate "0.3.0"]   
   [http-kit "2.1.16"]
   [ring-cors "0.1.4"]
   [compojure "1.1.7"]
   [clj-stacktrace "0.2.7"]
   [cljsbuild "1.0.4"]
   [clojurescript-build "0.1.3"]
   [watchtower "0.1.1"]
   [digest "1.4.3"]])
