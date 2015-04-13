(defproject lein-figwheel "0.2.7-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[figwheel-sidecar "0.2.7-SNAPSHOT"]]

  :profiles {
    :dev {
      :dependencies [[cljsbuild "1.0.5"]]
      :plugins [[lein-cljsbuild "1.0.5"]]}}
  
  :scm { :name "git"
         :url "https://github.com/bhauman/lein-figwheel"
         :dir "plugin"}

  :eval-in-leiningen true)
