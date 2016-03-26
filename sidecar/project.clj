(defproject figwheel-sidecar "0.5.2"
  :description "ClojureScript Autobuilder/Server which pushes changed files to the browser."
  :url "https://github.com/bhauman/lein-figwheel"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/lein-figwheel"
        :dir ".."}
  :dependencies
  [[org.clojure/clojure "1.7.0"]
   [org.clojure/clojurescript "1.7.228"
    :exclusions [org.apache.ant/ant]]
   [org.clojure/core.async "0.2.374"
    :exclusions [org.clojure/tools.reader]]
   [com.stuartsierra/component "0.3.0"]
   [http-kit "2.1.19"]
   [ring-cors "0.1.7"
    :exclusions [ring/ring-core org.clojure/clojure]]
   [compojure "1.4.0" :exclusions [org.clojure/clojure]]
   [clj-stacktrace "0.2.8"]
   [digest "1.4.4" :exclusions [org.clojure/clojure]]
   [figwheel "0.5.2"
    :exclusions [org.clojure/tools.reader]]
   [hawk "0.2.9" :exclusions [org.clojure/clojure]]

   ;; for config validation
   [clj-fuzzy "0.3.1"]
   [fipp "0.6.4"]])
