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