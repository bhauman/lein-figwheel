# 0.1.2 Fix for Java 9/10 

* fix classloader bug that prevented figwheel.main from starting Java 9/10
* fix NPE when nonexistant namespace is provided as main ns

# 0.1.1 Classpath Repair, Devtools and Helper App

* add helper app to provide contextual information when launching `figwheel.main`
* fix case when target directory is on the classpath but does not
  exist, as resources will not resolve in this case
* warn when there is a `resources/public/index.html` and the `resources`
  directory is not on the classpath
* ensure that the watched directories are on the classpath
* ensure that the watched directories have source files
* ensure the directory, that the `:main` ns is in, is on the classpath
* auto include `binaryage/devtools` in opt none builds targeting the browser
* fixed bug where providing a `:ring-handler` in build metadata didn't work
* fixed bug where single compiles with the -c option were not working for optimization
  levels other than none
* add `:client-log-level` option to set the level for client side logging
* fixed problem where node target builds were launching a browser
* fixed undefined var warnings in the REPL by ensuring full analysis on REPL start

# 0.1.0 Initial Release
