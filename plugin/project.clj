(defproject lein-figwheel "0.5.2-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser. This is the lein plugin."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[figwheel-sidecar "0.5.2-SNAPSHOT"]
                 [figwheel "0.5.2-SNAPSHOT"]]

  :scm { :name "git"
         :url "https://github.com/bhauman/lein-figwheel"
         :dir ".."}

  :eval-in-leiningen true)
 
