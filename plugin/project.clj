(defproject lein-figwheel "0.1.0-SNAPSHOT"
  :description "ClojureScript Autobuilder/Server which pushes changes to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "1.0.3"]]
  :dependencies [[cljsbuild "1.0.3"]
                 [figwheel "0.1.0-SNAPSHOT"]]
  :eval-in-leiningen true)
