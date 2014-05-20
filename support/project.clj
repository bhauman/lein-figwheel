(defproject figwheel "0.1.2-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm { :name "git"
        :url "https://github.com/bhauman/lein-figwheel"}  
  :dependencies
  [[org.clojure/clojure "1.6.0"]
   [org.clojure/clojurescript "0.0-2202"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
   [cljsbuild "1.0.3"]
   [me.raynes/fs "1.4.4"]
   [http-kit "2.1.16"]
   [compojure "1.1.7"]
   [watchtower "0.1.1"]
   [digest "1.4.3"]])
