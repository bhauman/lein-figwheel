(defproject figwheel-sidecar "0.2.6-SNAPSHOT"
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
   [org.clojure/core.async "0.1.346.0-17112a-alpha"]
   [com.cemerick/pomegranate "0.3.0"]   
   [http-kit "2.1.16"]
   [ring-cors "0.1.4"]
   [compojure "1.1.7"]
   [clj-stacktrace "0.2.7"]
   [cljsbuild "1.0.4"]
   
   [com.cemerick/piggieback "0.1.5"]
   [cider/cider-nrepl "0.8.2"]
   
   [clojurescript-build "0.1.5"]
   [watchtower "0.1.1"]
   [digest "1.4.3"]])
