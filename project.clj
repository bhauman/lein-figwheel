(defproject devserver "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "1.0.3"]]
  :dependencies [[cljsbuild "1.0.3"]
                 [cljschangeserver "0.0.1"]]
  :profiles {
             :dev {
                   :dependencies [[cljschangeserver "0.0.1"]]
                   :plugins [[lein-cljsbuild "1.0.3"]] 
                   }
             }                
  :eval-in-leiningen true)

