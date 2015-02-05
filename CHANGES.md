## 0.2.3-SNAPSHOT

* **Node.js support!**: figwheel and the built-in REPL now supports running
  in a Node.js evironment. This is very initial support for Node so if you
  run into trouble please let me know.
* **Possible Breaking Change** figwheel now reloads files (js/css) from where
  they are originally loaded from. This should work in almost every setup
  imaginable. The `:url-rewriter` client config option shouldn't be
  needed in most cases now. If you are currently using `:url-rewriter` it will
  be broken as the form of the url argument is changing.
* The above change means that you no longer are required to have your
  cljs target files on a resource path the figwheel server can see. If
  you are loading your app from the figwheel server you will need to
  have your output target files in an accessable resources directory
  otherwise place them where your server needs them. Or you can just
  load your app from the filesystem.
* **node-webkit** should also be much easier to use as a result of these
  changes
* **Fully loads dependents** a new feature in the CLJS compiler is that it
  recompiles all files that are dependent on a changed .cljs file. Figwheel is
  complementing this behavior by doing the same: when a file changes all
  its dependents are loaded as well, in correct dependency order.
  This has the marvelous benefit of reloading the application root file
  whenever one of its dependencies change. This can obviate the need for
  `:on-jsreload` but just having app restarting/rerendering code
  (like `React.render`) at the bottom of your root application file.
* `^:figwheel-always` can now be added as meta data to cljs namespaces that
  you want to be reloaded whenever there is a file change in your source
  tree. This will pretty much put an end to :on-jsload for even complex
  setups. These namespaces do not have to be required. So you can put
  this on your test runner namespace and viola you get tests running in
  the client env on every reload.
* No more undefined errors in the REPL when you try to define things
  in the `cljs.user` ns after refreshing the browser.
* better REPL support in general for (require :reload) and :reload-all
* `:debug` is a new client config option, when it is truthy figwheel
  will print out copious amounts of debug information.


## 0.2.2-SNAPSHOT

* **lein figwheel now launches a REPL into your application**, this REPL shares the
  compilation environment with the autobuilder, this makes the REPL pretty darn
  intelligent about what is loaded in your browser env already.
  For ex. do `(in-ns 'your.cljs.namespace)` and poke around  
* for a better repl experience launch lein figwheel with rlwrap
  `$ rlwrap lein figwheel`
  you can install rlwrap on OSX with brew: `brew install rlwrap`
* you can control the autobuild process from the CLJS REPL, no lein reboot
* the repl can be disabled with `:repl false` in the `:figwheel` config
* the logfile for figwheel server output can be configured with `:server-logfile` in
  `:figwheel` config  
* in 0.2.0 figwheel stopped honoring cljsbuild `:notify-command`, figwheel now honors
  `:notify-command` only on successful compiles, this should be especially helpful to
  those who are using `:notify-command` to run tests
* **requires `org.clojure/clojurescript "0.0-2665"` or greater**

## 0.2.1-SNAPSHOT

* now supports multiple builds i.e lein figwheel example example-admin
* refactored figwheel-sidecar/auto-builder and clojurescript-build/auto
  a bunch to facilitate better reuse

## 0.2.0-SNAPSHOT

* **extremely fast incremental builds when editing Clojure (.clj) files**
* you can now call the figwheel autobuilder/change-server from the repl or your
  own build script (not documented yet)
* isolated server and building code into to its own project to so that including
  the client code doesn't polute projects with clj based deps,
  the server and builder code only get's included in the plugin
* fixed bug where warning handler was getting added over and over in the build loop
* fixed bug: files that are't required don't get loaded but the console message 
  was saying that they were
* if you want to force an unrequired file to get loaded you can now do this
  `(ns ^:figwheel-load example.core `
* if you want to prevent a file from being reloaded you can now do this
  `(ns ^:figwheel-no-load example.setup `

## 0.1.7-SNAPSHOT

* compile warning notifications forwarded to client
* block file reload if compile warnings are present
  (there is still a race condition in cljsbuild that prevents this
   from working 100% of the time)
  to overide this use `:load-warninged-code true` in the figwheel client
* got rid of the `defonce` macro. It is built into cljs
* added a heads up display to the client!!! You can opt out of this with
  `:heads-up-display false` in the client config
* the heads up display 'can' trigger a file to open in your editor, if you do
  the work to get `emacsclient` or your editor's equivalent working correctly.
  In the `:figwheel` configuration in your `project.clj` you need to include
  an `:open-file-command` option. This should be the name of a script on your
  path that takes a file and a line number. The reccomendation here is to write
  your own script and put it in ~/bin.
* `:open-file-command` is `emacsclient` aware so you can just provide `"emacsclient"`
  as an option and it will just work.
* protection from connecting to another project by mistake
* > 46 commits and many more changes
* completely compatible with previous versions of figwheel unless you are using
  figwheel's `defonce` and not ClojureScripts

## 0.1.6-SNAPSHOT

* better configuration validation and feedback for configuration errors
* cleaned up, documented and added tests for plugin code
* @font-face tags caused cors error, added promiscuous CORS support
* added check for WebSocket support to prevent errors in IE8

## 0.1.5-SNAPSHOT

* fixed windows path bug
* made map based cljsbuild configurations work
* fixed :http-server-root option

## 0.1.4-SNAPSHOT

###
* Fixed LightTable incompatability
* Figwheel now respects :resource-paths and you can have your compiled files in any resource path
* Added :ring-handler configuration option to allow quck embedding of a dev server

## history starts here :)