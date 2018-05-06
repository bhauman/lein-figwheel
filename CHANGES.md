## 0.5.16 Caching improvements & no trampoline on windows

Daniel Compton did some great work solving the caching issues.

There is also a very confusing problem with lein trampoline on Windows
see issue #682. So I turned off the auto-trampoline feature for
Windows which allowed the for the elegent use of Rebel readline by
just calling `lein figwheel`.

Windows users can still elect to use trampoline with figwheel and get
the benefits of rebel-readline but the will have to call lein
trampoline figwheel and this should be done with the knowledge that
they may experience a classpath corruption issue.

* worker target support PR #659
* important caching tweaks for serving CLJS assets PR #667
* :npm-deps false support PR #678


## 0.5.15 Readline

A big thanks goes out to ClojuristsTogether.org and everyone who
contributed for helping to make this work possible!

This release includes the biggest change that Figwheel has had in
quite a while. After 2 months of work I'm proud to release a version
of Figwheel that has Clojure readline support. Even if you always use
your editor REPL like me, you may want to try the REPL that `lein
figwheel` launches. You may find that its a handy tool to have around.

You can learn more about rebel-readline at
[https://github.com/bhauman/rebel-readline](https://github.com/bhauman/rebel-readline).

Don't forget this gets rid of the need for `rlwrap`.

You can disable the Rebel readline by adding `:readline false` to your
`:figwheel` config.

This release requires that you use
`[org.clojure/clojurescript "1.9.946"]` at least.

This release also, adds automatic support for projects that use
[Code Splitting](https://clojurescript.org/guides/code-splitting). You
should now be able to use code splitting and have Figwheel "just work".

Other changes:

* Figwheel is now using cljs `:preloads` for figwheel client injection

## 0.5.14 Faster loading for complex dependency trees

Figwheel inherited a topo sort algorithm from the CLJS compiler. It
turned out that this graph sorting algorithm missed a very important
optimization, which is fixed in this release. If you have project that
has a lot of namespaces and have experienced slower load times when
you change a file that is deeper in your dependency chain, you should
notice a big improvement when using this release.

Added JavaScript environment hooks for tools like `re-natal` to
customize the websocket implementation and the script loading behavior.

* improved topo-sort algorithm
* CLJS compile option: the `:entries` key is no longer required in the
  `:modules` configs
* new `:repl-eval-timeout` allows you to increase or lower the
  Figwheel REPL eval timeout as a top level config setting
* added `goog.global.FIGWHEEL_WEBSOCKET_CLASS` to allow one to
  override or supply a websocket implementation for the figwheel
  client
* added `goog.global.FIGWHEEL_IMPORT_SCRIPT` to allow one to override
  or supply a function to the figwheel client, that is responsible for
  loading a namespace into the JavaScript runtime.
* added two new namespaces that can be supplied the ClojureScript
  compiler's `:preloads` option.
  
  The two namespaces are only different in that one set's up figwheel
  while the other both sets up and starts figwheel. These are
  currently meant to clean up how the figwheel client is injected into
  the build. The next release of Figwheel will do away with generating
  a small ClojureScript file to inject the Figwheel client into build.
  - `figwheel.connect` which will take a configuration from
    `:external-tooling` > `:figwheel/config` and supply a
    `figwheel.connect/start` function which contains the supplied
    config options. This function is exported so that it can be easily
    called from JavaScript.
  - `figwheel.preload` which simply calls the above `figwheel.connect/start`
    function

## 0.5.13 Small updates

* remove the use of a deprecated Google Closure library function 
  goog.net.jsloader.load
* add CLJS compile option `:process-shim` to validation code

## 0.5.12 Cache busting and more compiler option updates

There have been constant problems when reloading an application into
Chrome where even a hard reload insists on pulling items from the
cache. Adding `no-cache` to the response headers headers and returning
appropriate `not-modified` responses is a trade-off that will
hopefully address this issue without adversely affecting initial load
performance. Keep in mind that this will only apply to Figwheel's built-in
dev server. If this becomes a problem for some projects we can explore
adding a flag for this behavior.

* let :npm-deps use any named type for the keys 
* allow the server to serve symlinks outside of the project
* take more care when checking localStorage for persistent config
* add suspendable interface implementation to figwheel system component
* add :checked-arrays option
* update the catalog of :warning and :closure-warning options
* fixes uses of `aget`
* make configuration smiley paredit friendly

## 0.5.11 New cljs compiler options

* add CLJS compile option `:fn-invoke-direct` to validation schema
* add CLJS compile option `:source-map-asset-path` to validation schema
* add CLJS compile option `:load-test` to validation schema
* add CLJS compile option `:npm-deps` to validation schema
* only watch directories if they exist - PR #551 fixes Issue #451

I knew when I started validating compiler options that there was a
trade-off. On the one hand I can create really specific errors
(i.e. spelling errors) if I do a hard check for key membership, but
with the down side that as figwheel trails the compiler development
new options would couse the config to fail validiation.

I figured this would be a good trade off considering that:

A. Compiler options will be added at a slower and slower rate as time
goes on.

B. In general newer compiler options are geared towards more advanced
users who understand the trade-off of opting-out of config validation.

One can opt-out/tune this behavior with the `:validate-config` option:

    :validate-config (false | :warn-unknown-keys | :ignore-unknown-keys)

and skip unknown key validation all together. 

## 0.5.10 Init and destroy hooks

In the top level figwheel config one can now place some lifecycle
hooks, to easily add more services to the process that figwheel is
running in.

For example:


    :figwheel { :init    user/start-server
                :destroy user/stop-server }

Also:

* loosen :clojure-defines validation

## 0.5.9 JavaScript reloading for Foreign lib dirs and pprint REPL results

* seemless hot reloading of JavaScript in foreign lib dirs
  This works for the new Javascript module and pre-processing 
  functionality introduced in CLJS 1.9.456
* added pprint for repl eval results and tools to turn it on and off
* display the number of connections in the REPL
* support for webworkers added PR #498
* send errors thrown when we try to reload a clojure file up to the HUD
* added :compile-paths and :watch-paths
  - :compile-paths will override :source-paths and determine the paths given to the compiler
  - :watch-paths will override :source-paths and determine the paths given to the filewatcher
  - :source-paths will always determine the paths to be added to the classpath
* fix hot reloading on node for CLJS 1.9.456
* support lein's managed dependencies
* text-align left forced for the HUD displays
* fixed css reloading race condition
* fixed clj file reloading so that only macro files trigger reloads of CLJS dependents
* fixed ^:figwheel-no-load
* added :auto-clean option to allow the dissabling of figwheel's auto cleaning
* reverted the inlining of figwheel connect cljs code and dump it to the filesystem
* fixed (require) in the figwheel repl
* fixed the reported output directory of a changed file
* fix bug where figwheel connection scripts were being reloaded
* clarify message when starting nrepl without piggieback
* fix bugs around having :figwheel > :builds hold your build configs
* :provides in :foreign libs is not a required option
* added :infer-externs to schema
* add cljs-config :closure-warnings to the config spec
* add cljs-config :warnings to the config spec

## 0.5.8 Fix for Clojure 1.9.0-alpha12

* small fix for recent Clojure alpha
* vim-fireplace fix: merged https://github.com/bhauman/lein-figwheel/pull/476 from 
  Juho Teperi and Dominic Monroe

## 0.5.7 More small fixes

* add :start to the :validate-interactive options
* fix the cljs options modules spec
* made the :open-urls feature work better

## 0.5.6 Small fix release

* fixes issue #468 where hyphenated builds are failing to connect
* fixes issue #467 where validation runs into an unparsable project.clj path

## 0.5.5 Config Validation with code context

* added configuration validation based on clojure.spec
* set :validate-interactive to false to prevent figwheel from entering 
  validation correction loop
* new options for :vaidate-config - besides the boolean option you can
  specify :warn-unknown-keys and :ignore-unknown-keys
* new client option :auto-jump-to-source-on-error if set to true
  will cause the client to call the open-file-command script
  as soon as the heads up display displays an error or warning
* setting :server-logfile to false will redirect all output to *out*
* included a fix for vim-fireplace
* heads-up error code context is now scrollable
* make sure all output flows to *out* when :repl is false
* ensure that all build :output-dir paramaters are unique
* only make system calls for websocket-url format if they are required
* the fighweel/connect.cljs is no longer output to disk but is 
  passed to the compiler in memory
* the generated connect code has a unique namespace depending on the build id
* fixed google closure removal of dom/htmlToDocumentFragment
* added on-cssload custom event

## 0.5.4-7 fix :open-command

* PR #449 Arguments to open-file-command must be strings, not numeric.

## 0.5.4-6 update sidecar system apis to handle result of fetch-config

* PR #447 change the validator to allow ES6 and :no-transpile 
* #440 allow figwheel system to take the result of (config/fetch-config) 

## 0.5.4-5 quick fix

* protecting against bad data broke Map style :builds declaration, fixed

## 0.5.4-4 added :preloads compiler option

#### Removed compojure as a dependency

Figwheel used
[Compojure](https://github.com/weavejester/compojure/tree/master/src/compojure)
in its server to handle routing. This was convenient but was also overkill
for the simple routing that Figwheel needs. This also made the routing
a bit more complex for the downstream `:ring-handler`.

Just recently a strange issue
https://github.com/bhauman/lein-figwheel/issues/428 popped up. Strange
things where happening when `wrap-reload` was being used in an
embedded `:ring-handler`.

There is so little routing functionality in Figwheel that I just
replaced the Compojure routing with simple Ring middleware. This is much more
predictable and has the added advantage of removing another
dependency from Figwheel.

If the behavior of your `:ring-handler` changes (routes not being
resolved, bad/missing HTTP headers etc.)  please let me know.

For reference here is the commit:
https://github.com/bhauman/lein-figwheel/commit/f027b10188ed9d1baa6ec04bbdd14e6a493f68b0

#### Improved the resiliency of the plugin around bad initial config data

The lein plugin uses config data from the project.clj (or
figwheel.edn) before it has been validated, I added some extra safe
guards to protect against initial use of this data.

#### added validation support for :preloads compiler option


## 0.5.4-3 some improvements around starting figwheel from the REPL

* I added back `figwheel-sidecar/get-project-builds` which was removed in 0.5.4
  its now here to stay.

#### Improved the expressiveness of (start-figwheel!)

before `(start-figwheel!)` only optionally took a configuration. Now it
takes optional build-ids as well.

So you can do this:

```
(start-figwheel! "dev" "admin")
```

And it will pull in the config from the environment and start
autobuilding the supplied build ids.

Or you can supply a config and build ids ...

```
(start-fighweel!
  {:figwheel-options {:server-port 4000}
   :all-builds [{:id ...}]}
   "dev" "admin")
```

I have also beefed up the error checking and feedback around this call.


## 0.5.4-2 Quick fix for failure due to composite lein profiles

* my profile merging code was choking on composite lein profiles

## 0.5.4 Error messages and way more!

This is the most solid release of Figwheel so far ... enjoy!!

#### Code context in Errors and Warnings

This means you will see the compile errors and warnings displayed
along with the offending code and a pointer to the position of the
failure. This is a big improvement. I've found that even for simple
errors, that I've become accustomed to, the new errors tend to beam the
information into my head much more quickly.

The code pointers are rough at times and no code context information
will be displayed if Figwheel doesn't get any line and column information
from the compiler.

The new error display can be improved further, and I plan to do so.  I
just wanted to get the bits hooked up and a decent display out the
door first.

* added code context to compile errors in heads up display,
  figwheel_server.log and REPL output
* added code context to compile warnings as well

#### Improved Build correctness

There have been several correctness problems that have dogged Figwheel
for a while.

The most important change here is that I have made all code that runs
in the plugin only depend on libs that are either natively available
in Clojure or are included with Leiningen. This should rid us of the
many ways that Figwheel was incompatible with other plugins and
environments. I really had to learn the hard way that this is the only
way to develop Leiningen plugins, and in hindsight, it makes a lot of sense.

A downside of this is that Figwheel "appears" to start slower. But all
my tests have shown that it is starting slightly faster.  And .... as
a bonus `lein trampoline` works very well with Figwheel now.

If you are not already familiar with `lein trampoline`, the following
command will cache the startup args the first time it is run, and will
only launch one JVM on the next execution.

```
LEIN_FAST_TRAMPOLINE=y lein trampoline figwheel
```

In my setup I have the following script in on my path. Using it shaves seconds
off of my startup time

file::/User/bhauman/bin/figt
```
#!/bin/bash

LEIN_FAST_TRAMPOLINE=y rlwrap lein trampoline figwheel "$@"
```

* I have added startup and runtime checks to ensure that the various
  Figwheel library versions match.
* I added a check to the client that will warn you if you are getting messages
  from a server that is a different version than the client.
* Automatically clean a cljs build if the classpath changes or if the
  build `:source-paths` change or if certain ClojureScript compiler
  options change. This means that if you add a dependency in your
  project.clj the next time you start figwheel it will detect that
  change and clean out the build assets before compiling. If you have
  a simple classpath that points to a directory of jars this won't
  help you, but I think this will help a great majority of users.

#### DOA helper application

This is a user experience improvement experiment.  There has been a
problem when the initial compile fails and you open the browser and
nothing happens and your REPL doesn't work.

Inspired by Elm, I am now emitting a small application that announces
the problem when you load it in the browser. This application is a
figwheel client so that it will respond to and display compilation
messages and will provide a REPL execution environment.

It won't load compiled files but as soon as a compilation succeeds it
will auto-refresh and thus pick up your correctly compiled application.

It's still early but I'm thinking that this could be a good feature.

#### Basic Leiningen profile merging

Folks who have been using `(figwheel-start!)` from the REPL have been
confused by Figwheel's inability to merge in the default Leiningen
profiles.

Figwheel will now merge Leiningen profiles without needing to load
`leiningen-core`.

This behavior is new and not comprehensive but will probably work fine
for the majority of cases where someone has a little profile merging
in their project.clj

#### Added commands

The Figwheel lein plugin has added commands and better command-line
feedback for bad args.

The new commands are

`lein figwheel :once build-ids ...`

Which will do what `lein cljsbuild once` does, with figwheel error
messages. This command won't inject the figwheel client code.

`lein figwheel :check-config`

Which will run validation on your configuration.

`lein figwheel :help`

Which prints out the same help information as `lein help figwheel`

You should take a moment and read this help information.

Expect a `:watch` command in the next release....

#### Getting rid of `:build-options` in favor of `:compiler`

A younger, more idealistic version of myself, wanted to have a different
key for compiler options.  So I used `:build-options` internally. As
usual that younger version of myself had no idea what he was doing.

If you are using `:build-options` you will get a validation error
saying that it is deprecated and that you should use `:compiler`.
`:build-options` is still being used internally but I am saving it as
technical debt.

#### Lots more

* huge configuration re-factor so that `(start-figwheel!)` now validates the configuration
  and throws useful exceptions if a configuration problem is found
* fixed printing in the REPL, before you couldn't call `(println "hi")`
  and see the output in the REPL.  This works for nREPL as well as a
  direct `cljs.repl`.
* made printing much more robust, executing `(js/setInterval #(println "hi") 1000)`
  in the REPL works sending output to the REPL
* fixed REPL js runtime stacktraces, these must have been broken for a
  while, sorry about that
* a new `:open-urls` option in the per-build client `:figwheel` configuration.
  This is a vector of URLs that Figwheel will open the first time a
  build completes. These are opened with `clojure.java.browse/browse-url`.
  You could put `http://cljs.info/cheatsheet/` info in there :)
* fail fast if figwheel server doesn't start because of a port bind error
* disable ansi colored output with `:figwheel > :ansi-color-output false`
* figwheel now provides the `:open-file-command` script a third
  argument that is the column position of the problem. Yes this means
  that heads up display errors can pop you to the line and *column*
  if you click on them.
* beefed up logging around `:open-file-command` so that one can debug it more easily  
* redirected `:ring-handler` exceptions and general printing output to the
  figwheel-server.log
* ring exceptions and stack traces are displayed in the response HTML as well  
* fixed a problem where `window.location` was being called in a node environment
* if you want to limit the classpath to only use the paths from the specified builds
  (i.e. builds supplied on the command line) you can set `:figwheel > :load-all-builds false`
* removed awt beep from source code to remove the annoying Java system icons
* fixed `:repl false` configuration option
* fixed and improved `(reload-config)` REPL command, reports config errors now



## 0.5.3-2 Fix regression for initial build errors

* cleaned up initial compile failure experience. There are horrendous errors
  if you start figwheel and you have a syntax error in your initial build.
  #fixes 392
* hopefully fixed CustomEvent dispatch in IE Edge made
* :source-map-timestamp default to true unless specifically set to false.
  This is for :figwheel builds only.
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
