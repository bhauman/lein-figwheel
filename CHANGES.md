## 0.5.3-2 Fix regression for initial build errors

* cleaned up initial compile experience and horrendous errors in this common case
  When you started figwheel and you have a syntax error in your code the experience
  was really less than satisfactory. I have improved this, and have work planned to
  improve this further.
* hopefully fixed CustomEvent dispatch in IE Edge made
* :source-map-timestamp default to true unless specifically set to false.
  This if for :figwheel builds only.
* fixed a problem with :js-client-host, it now uses the localhost.hostname + server-port
* Added :websocket-url template tags, see the Readme.
* When reading raw project.clj extract data only from first form that starts with 'defproject

## 0.5.3-1 First take on improving compiler error messages

I'm planning on investing some time in fixing up the error messages. This is just a
first take.

## 0.5.3

* fail fast if trying to launch cljs REPL in nREPL environment and piggieback is not available
  fixes issue #347
* passing :notify-command a more descriptive message, and call it on the very first build
  much more system notification friendly now, fixes PR #376
* PR #369 added :server-hostname and :server-ip as options for build > :figwheel > :websocket-host
  this will insert host specific info to the figwheel client config
* PR #371 added :closure-output-charset compiler option to config validation
* moved to Clojure 1.8.0
* add :nrepl-host to configuration validation
* PR #363 print out actual server ip in "Figwheel: Starting server at"

## 0.5.2 Quick fix for Windows

* fix null pointer because of bad resource path to config docs

## 0.5.1 Configuration Validation

* added a fairly comprehensive configuration validation that is triggered
  when running 'lein figwheel' - there is a large surface area here so if
  you discover problems please report them

## 0.5.0-5 More work on system dependency conflicts

* adding system asserts to ensure and warn if not using Java 1.8 or Clojure 1.7.0
* adding polling option for hawk file watcher; just add :hawk-options {:watcher :polling} to base figwheel config
* added friendlier 404 page

## 0.5.0-4

* finally fixing the dependency conflicts

## 0.5.0-3

* change repl-api commands like `figwheel-start!` so that they don't return the SystemMap
  as this was behaving badly in various REPL configurations 28e03af
* figwheel-running? checks if figwheel is actually running now bb146e1
* guard access to localStorage on client fdb49a2
* Print exception when requiring ring handler fails f663acc
* add support for notify-command back a475c59
* Add exclusions to fix sidecar confusing dependencies b263835
* For custom ring handler, bind to var, not to its value 789ff03
* create directories for logfile ce39ac0

## 0.5.0-2

* bumping http-kit to 0.2.19 get's rid of nasty exit exception
* fix REPL helpers check for figwheel's running status
* fixed REPL problem where pprint would produce extra output

## 0.5.0-1

* added back `figwheel-sidecar.repl/get-project-cljs-builds` and deprecated
* made the repl special-fn `(build-once)` work for builds that aren't opt none
* made the repl special-fn `(clean-builds)` work for builds that aren't opt none

## 0.5.0 Complete Refactor

* Figwheel is has undergone a large refactor and is now working better than ever
* Figwheel is now internally composed of components and has been
  re-organized according to concerns - this is not quite finished yet so the internal api may be be unstable
* this exposes the ability to compose component systems as you see fit and offering the ability to
  send messages from your own components to the figwheel client
* moved to a FSEvent file watching strategy using the hawk library, this
  should ease CPU usage quite a bit
* you can override the build function with the new `:cljs-build-fn` figwheel option
* you can opt out of `.clj` or `.cljc` macro reloading
* the build function follows a middleware strategy allowing you to splice in build time functionality
  - this can easiliy allow you to skip all magic figwheel injections and just run straight `cljs.build.api/build`
* hot loading javascript has been solidified even further
* figwheel repl controls are much more sensible now, they alter a component system map
* fixed various build config "parsing" problems
* support for an external configuration file "figwheel.edn"
* figwheel no longer overides `:recompile-dependents` default value bringing it inline with cljs compiler defaults
* much much much more ...

## 0.4.1

* fixed bug where GCL javascript modules with '-' in the namespace were not being hot reloaded
* fixed Node.js support on windows (a path error ... who'd have guessed??)
* improved the api for starting and stopping figwheel from scripts and such
* added a way to quit out of the cljs-repl build choosing loop

## 0.4.0

* `:nrepl-middleware` server configuration has been added (see readme)
  Thanks to Okke Thijhuis @otijhuis, give him a shout out!
* both cider and piggieback have been removed, you must include the deps you
  want in your project.clj.
* fix for #233: a stacktrace bug in the REPL
* update string/replace usage, this could have affected REPL evaluations: thanks to @nikitonsky
* make `:ring-handler` a var so it picks up changes - thanks to @nikitonsky
* toggle auto loading on and off quickly with `(figwheel.client/toggle-autoload)` or `figwheel.client.toggle_autoload()` in the dev console

## 0.3.9

* fixes incompatability with weasel and cljs browser repl

## 0.3.8

#### Incompatable with weasel repl and others that use the cljs browser repl. Expect a new release very soon.

* **Hot reloading Javascript!** this works for `:foreign-libs`, `:libs`
  and Google Closure libs in your `:source-paths` that follow Clojure
  namespacing conventions - this is hot!
* **fixed the loading order of dependencies** this silently broke a while back. Dependency
  loading has been overhauled to work with the latest CLJS and should be much more stable.
* `(require ... :reload)` and `(require ... :reload-all)` work correctly now
* adding `:reload-dependents` client config parameter. It forces the "reloading"
  of files that are dependent on changed files. This is very fast operation and can
  potentially obviate the need for `^:figwheel-always`
* **removed cljsbuild as a dependency**, this removes **crossovers** and **notify-command** from figwheel
* new Community CLJS logo
* fixing Node so that `figwheel.connect` works
* added http PATCH to CORS


## 0.3.7

* update to clojure 1.7.0!
* hopefully fix #194 update http-kit 

## 0.3.6

* no change

## 0.3.5

* small fixes to allow Atom.io usage see pr-188 and pr-189 for details
* small devcards tweak

## 0.3.4

* fix #183 htmlEscape warnings and errors in the heads up display
* fix #179 add `:server-ip` server config option to all folks to not
  default to binding "0.0.0.0" which exposes the connection to the network
* add `:devcards` option to figwheel client config, more on this later
* add `:websocket-host :js-client-host` option to allow the client to use the `location.host` of
  the loaded page. Thanks to @peterschwarz
* fix #180 cider no longer hard coded to repl, but auto detected, allows folks
  to use `[cider-nrepl 0.9.0]` a little more easily
* making reloading of build config an explicit `reload-config` special-fn in the repl
* fix #164 explicitly close the client side websocket on onload

## 0.3.3

* ensure that we are only adding inserting the figwheel.connect require when `:figwheel` is
  set in the build

## 0.3.2

* provide global custom events "figwheel.js-reload" and "figwheel.before-js-reload" for folks to hook into
* initial build wasn't providing figwheel start hook to output-to file
* merge PR #150 allow to configure binding host of nREPL 
* bump clojure requirement to be inline with clojurescript "0.1.7beta3" to fix #152
* fix #151 stop depending on presence of project.clj 
* fix #147 handle presence of a deps.cljs on the source path 
* fix #145 cannot read property cljs$lang$maxFixedArity
* merge PR #146 correctly detect if in html document 

## 0.3.1

* fixed regression on supporting map based build configs

## 0.3.0

* the `(reset-autobuild)` REPL command now reloads the build config
  from your `project.clj`
* simplified necessary build config parameters, no longer have to have
  `:optimizations`, `:output-to`, `:output-dir`

## 0.2.9

* fixes #137 missing connect.cljs 

## 0.2.8

* vastly simplified configuration. You no longer need to write the
  client code manually and then figure out how to have it excluded in
  production. Simply add `:fighweel true` to builds that you want
  figwheel to run on. The client code will be injected to the build.
  You can inspect the generated client code in
  `target/figwheel_temp/<build id>/fighweel/connect.cljs`
* fixed #126 where inclusion of figwheel.client in an advenced build
  caused "undefined is not a function" problems. This is fixed please
  don't include figwheel.client in advanced builds. It won't work.
* fixed #124

## 0.2.7

* **support Reader Conditionals**
* require cljs-3165
* re-fixed #118 topo-sort incorrect implementation
* heads up display includes file and line information for reader exceptions
  - Thanks to Juho Teperi 

## 0.2.6

* fixed shared compile-env for cljs-3196
* fixed #108 double file loading
* fixed #107 repl `:false` starts two compile processes
* fixed #118 topo-sort incorrect implementation
* fixed #106 empty build `:id` caused NPE
* fixed #65  friendly error for already bound port error
* upgraded cljsbuild
* upgraded figwheel-sidecar dependencies

## 0.2.5

* **First non snapshot release**
* `^:figwheel-always` was causing a double file reload when editing the marked ns
* minor fixes to be compatible with ClojureScript REPL changes
* CSS changes broadast to all named builds, this should work fine as long as
  CSS files for different builds are named distinctly
* fixed problem where using unquoted namespaces in `:modules` configurations was
  preventing figwheel from starting

## 0.2.5-SNAPSHOT

* **nREPL and cider support for the REPL** adding an `:nrepl-port` along
  with a port number to the `:figwheel` config in your project.clj will
  start an nREPL server into the running figwheel process.
  https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl
* **disabling hod loading for REPL only development**
  Setting a new client side config option `:autobuild` to `false` will disable
  the automatic loading of code on file changes. Now that the cljs REPL
  development experience is maturing it's nice to have the option to do
  REPL only development. Its a client side option so that you can change
  back and forth between the styles of development fairly easily.

## 0.2.4-SNAPSHOT

* adds source mapped stacktraces to REPL
* does away dependents calculations when `:recompile-dependents` is `false`
* corrects a complexity bug in calculating dependents
* requires ClojureScript 0.0-2843 or greater

## 0.2.3-SNAPSHOT

* **Node.js support!**: figwheel and the built-in REPL now supports running
  inside a Node.js evironment. This is very initial support for Node so if you
  run into trouble please let me know.
* **Possible Breaking Change**: Figwheel now reloads files (js/css) from where
  they are originally loaded from. This should work in almost every setup
  imaginable. The `:url-rewriter` client config option shouldn't be
  needed in most cases now. If you are currently using `:url-rewriter` it will
  be broken as the form of the url argument is changing.
* The above change means that you no longer are required to have your
  cljs target files on a resource path the figwheel server can see. If
  you are loading your app from the figwheel server the current :output-dir
  requirement still stands.  If you are not going to load your app from the
  figwheel server then you you can place the files wherever you want.
* **node-webkit** should also be much easier to use as a result of the above
  changes
* **Fully loads dependents**: A new feature in the CLJS compiler is that it
  recompiles all files that are dependent on a changed .cljs file. Figwheel is
  complementing this behavior by doing the same: when a file changes all
  its dependents are loaded as well, in correct dependency order.
  This has the marvelous benefit of reloading the application root file
  whenever one of its dependencies change. This can obviate the need for
  `:on-jsload` but just having app restarting/rerendering code
  (like `React.render`) at the bottom of your root application file.
* `^:figwheel-always` can now be added as meta data to cljs namespaces that
  you want to be reloaded whenever there is a file change in your source
  tree. With this flag you won't really need `:on-jsload`, even for complex
  setups. Namespaces marked `figwheel-always` do not have to be required.
  So you can put this on your test runner namespace and viola!, you will have
  your tests running in the client env on every reload. Pretty cool.
* No more undefined errors in the REPL when you try to define things
  in the `cljs.user` ns after refreshing the browser.
* better REPL support in general for (require :reload) and :reload-all
* `:debug` is a new client config option, when it is truthy figwheel
  will print out copious amounts of debug information.
* `:load-unchanged-files` is a client option that if set to `false` will cause
  the client to not reload files that haven't changed. With this option set to
  `false` files will only load if their *content* has changed. This option defaults
  to `true`.

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
