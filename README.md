# lein-figwheel

[![CircleCI](https://circleci.com/gh/bhauman/lein-figwheel.svg?style=svg)](https://circleci.com/gh/bhauman/lein-figwheel)

Figwheel builds your ClojureScript code and hot loads it into the browser as you are coding!

# A new Figwheel!!

There is a new Figwheel in town!

[Figwheel Main](https://figwheel.org) is a
complete re-write of Figwheel and represents the latest and greatest
version of Figwheel. It works great with Leiningen or the new Clojure
CLI Tools.

So head over to
[Figwheel Main](https://github.com/bhauman/figwheel-main) to give it a
try.

# lein-figwheel

Get a quick idea of what figwheel does by watching the
6 minute [flappy bird demo of figwheel](https://www.youtube.com/watch?v=KZjFVdU8VLI).

Learn even more by watching a 45 minute [talk on Figwheel](https://www.youtube.com/watch?v=j-kj2qwJa_E) given at ClojureWest 2015.

Read the [introductory blog post](http://rigsomelight.com/2014/05/01/interactive-programming-flappy-bird-clojurescript.html).

## Support Figwheel

If Figwheel has fundamentally redefined the way you do front-end work
please take a moment and support it:

<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=B8B3LKTXKV69C">
<img src="https://s3.amazonaws.com/bhauman-blog-images/Smaller%2BDonate%2BButton%402x.png" width="200">
</a>

Donated so far: &nbsp;&nbsp;&nbsp; 2015: $73 &nbsp;&nbsp;&nbsp; 2016: $2752 &nbsp;&nbsp;&nbsp; 2017(through October): $1979

#### Current version:
[![Clojars Project](https://clojars.org/lein-figwheel/latest-version.svg)](https://clojars.org/lein-figwheel)

![Figwheel heads up example](https://s3.amazonaws.com/bhauman-blog-images/figwheel_image.png)

## Features 

#### Live code reloading

If you write [**reloadable
code**](https://github.com/bhauman/lein-figwheel#writing-reloadable-code),
figwheel can facilitate automated live interactive programming. Every
time you save your ClojureScript source file, the changes are sent to
the browser so that you can see the effects of modifying your code in
real time.

#### Supports Node.js

You can [use figwheel to live code ClojureScript in Node.js](https://github.com/bhauman/lein-figwheel/wiki/Node.js-development-with-figwheel)!

#### Static file server

The inclusion of a **static file server** allows you to get a decent
ClojureScript development environment up and running quickly. For
convenience there is a `:ring-handler` option so you can load a ring
handler into the figwheel server.

#### Live CSS reloading

Figwheel will reload your CSS live as well.

#### Live JavaScript reloading

Figwheel can live reload your JavaScript source files.

#### Heads up display

Figwheel has a non-intrusive heads up display that gives you feedback
on how well your project is compiling. By writing a shell script you
can click on files in the heads up display and they will open in your
editor!

#### Descriptive Errors with Code Context

Figwheel provides descriptive compiler errors that point to where
the error is in your code.  These errors appear in the REPL as well
as the heads up display.

#### First Class Configuration Error Reporting

It can be quite daunting, when you are configuring a tool for the
first time.  Figwheel currently offers best-of-class configuration
error reporting that will help you if you happen to misconfigure
something.

#### Built-in ClojureScript REPL

When you launch Figwheel it not only starts a live building/reloading
process but it also optionally launches a CLJS REPL into your running
application. This REPL shares compilation information with the
figwheel builder, so as you change your code the REPL is also aware of
the code changes. The REPL also has some special built-in control
functions that allow you to control the auto-building process and
execute various build tasks without having to stop and rerun lein-figwheel.

#### Robust connection

Figwheel's connection is fairly robust. I have experienced figwheel
sessions that have lasted for days through multiple OS sleeps. You can
also use figwheel like a REPL if you are OK with using `print` to output
the evaluation results to the browser console.

#### Message broadcast

Figwheel **broadcasts** changes to all connected clients. This means you
can see code and CSS changes take place in real time on your phone and
in your laptop browser simultaneously.

#### Respects dependencies

Figwheel will not load a file that has not been required. It will also
respond well to new requirements and dependency tree changes.

#### Calculates minimal reload set

Figwheel does its best to only reload what needs to be reloaded. This
minimizes the surface area of dynamically reloaded code, which in turn
should increase the stability of the client environment.

#### Doesn't load code that is generating warnings

If your ClojureScript code is generating compiler warnings Figwheel
won't load it. This, again, is very helpful in keeping the client
environment stable. This behavior is optional and can be turned off.

## Try Figwheel

Make sure you have the [latest version of leiningen installed](https://github.com/technomancy/leiningen#installation).

You can try figwheel out quickly with the flappy bird demo:

    git clone https://github.com/bhauman/flappy-bird-demo.git

then cd into `flappy-bird-demo` and type

    lein figwheel

You can now goto `localhost:3449/index.html` and open up
`src/flappy_bird_demo/core.cljs` with your favorite editor and start
coding. Make sure you open your browser's development console so you
can get feedback about code reloads.

If you would prefer to greenfield a new project you can use the
figwheel leiningen template.

    lein new figwheel hello-world

Or optionally:
```
    lein new figwheel hello-world -- --om       ;; for an om based project
    lein new figwheel hello-world -- --reagent  ;; for a reagent based project 
```

## Learning ClojureScript

If you are brand new to ClojureScript it is highly recommended that
you do the [ClojureScript Quick
Start](https://clojurescript.org/guides/quick-start)
first. If you skip this you will probably suffer.

There is a **lot to learn** when you are first learning ClojureScript,
I recommend that you bite off very small pieces at first. Smaller bites than
you would take when learning other languages like JavaScript and Ruby.

Please don't invest too much time trying to set up a sweet development
environment, there is a diverse set of tools that is constantly in
flux and it's very difficult to suss out which ones will actually help
you. If you spend a lot of time evaluating all these options it can
become very frustrating. If you wait a while, and use simple
tools you will have much more fun actually using the language itself.

## Quick Start 

If you are new to Figwheel here is a [Quick
Start](https://github.com/bhauman/lein-figwheel/wiki/Quick-Start) tutorial.
Working through this Quick Start will probably save you a tremendous
amount of time.

## Getting Help

You can get help at both the [ClojureScript Google Group](https://groups.google.com/forum/#!forum/clojurescript)

and on the **#clojurescript**, **#lein-figwheel** and **#beginners** [Clojurians Slack Channels](http://clojurians.net)

## Usage

Make sure you have the [latest version of leiningen installed](https://github.com/technomancy/leiningen#installation).

Then include the following `:dependencies` in your `project.clj` file.

```clojure
[org.clojure/clojure "1.9.0"]
[org.clojure/clojurescript "1.10.238"]
```

Then include `lein-figwheel` in the `:plugins`
section of your project.clj.

```clojure
[lein-figwheel "0.5.18"]
```

#### Configure your builds

You also need to have your `:cljsbuild` configuration set up in your
`project.clj`.

Here is an example:

```clojure
:cljsbuild {
  :builds [ { :id "example" 
              :source-paths ["src/"]
              :figwheel true
              :compiler {  :main "example.core"
                           :asset-path "js/out"
                           :output-to "resources/public/js/example.js"
                           :output-dir "resources/public/js/out" } } ]
}
```

The important part here is that you have to have at least one `build`
that has `:optimizations` set to `:none` or `nil`.

If you leave out the `:optimizations` key the ClojureScript compiler
will default to `:none`.

Setting `:figwheel true` or `:figwheel { :on-jsload "example.core/reload-hook" }` will
automagically insert the figwheel client code into your application.
If you supply `:on-jsload` the name of a function, that function will
be called after new code gets reloaded.

**If you want to serve the HTML file that will host your application
from figwheel's built in server**, then the output directory has to be
in a directory that can be served by the static webserver. The default
for the webserver root is "resources/public" so your output files need
to be in a subdirectory of "resources/public" unless you change the
webserver root. For now the webserver root has to be in a subdirectory
of `resources`.

If you are serving your application HTML from your own server you can
configure `:output-to` and `:output-dir` as you like.

Start the figwheel server. (This will get the first `:optimizations`
`:none` build)

    $ lein figwheel

You also have the option to specify one or more builds

    $ lein figwheel example
    $ lein figwheel example example-devcards

This will start a server at `http://localhost:3449` with your
resources being served via the compojure `resources` ring handler.

So you can load the HTML file that's hosting your ClojureScript app
by going to `http://localhost:3449/<yourfilename>.html`

If you are using your own server please load your app from that server.

### Figwheel server side configuration

This is not necessary but you can configure the figwheel system. At
the root level of your `project.clj` you can add the following server
side configuration parameters:

```clojure
:figwheel {
   :http-server-root "public" ;; this will be in resources/
   :server-port 5309          ;; default is 3449
   :server-ip   "0.0.0.0"     ;; default is "localhost"

   ;; CSS reloading (optional)
   ;; :css-dirs has no default value 
   ;; if :css-dirs is set figwheel will detect css file changes and
   ;; send them to the browser
   :css-dirs ["resources/public/css"]

   ;; Server Ring Handler (optional)
   ;; if you want to embed a ring handler into the figwheel http-kit
   ;; server
   :ring-handler example.server/handler

   ;; Clojure Macro reloading
   ;; disable clj file reloading
   ; :reload-clj-files false
   ;; or specify which suffixes will cause the reloading
   ; :reload-clj-files {:clj true :cljc false}

   ;; To be able to open files in your editor from the heads up display
   ;; you will need to put a script on your path.
   ;; that script will have to take a file path, a line number and a column
   ;; ie. in  ~/bin/myfile-opener
   ;; #! /bin/sh
   ;; emacsclient -n +$2:$3 $1 
   ;;
   :open-file-command "myfile-opener"

   ;; if you want to disable the REPL
   ;; :repl false

   ;; to configure a different figwheel logfile path
   ;; :server-logfile "tmp/logs/figwheel-logfile.log" 

   ;; Start an nREPL server into the running figwheel process
   ;; :nrepl-port 7888

   ;; Load CIDER, refactor-nrepl and piggieback middleware
   ;;  :nrepl-middleware ["cider.nrepl/cider-middleware"
   ;;                     "refactor-nrepl.middleware/wrap-refactor"
   ;;                     "cemerick.piggieback/wrap-cljs-repl"]

   ;; if you need to watch files with polling instead of FS events
   ;; :hawk-options {:watcher :polling}     
   ;; ^ this can be useful in Docker environments

   ;; if your project.clj contains conflicting builds,
   ;; you can choose to only load the builds specified
   ;; on the command line
   ;; :load-all-builds false ; default is true
} 
```

## Client side usage

Make sure you have setup an html file to host your cljs. For example
you can create this `resources/public/index.html` file:

```html
<!DOCTYPE html>
<html>
  <head>
  </head>
  <body>
    <div id="main-area">
    </div>
    <script src="js/example.js" type="text/javascript"></script>   
  </body>
</html>
```

## CSS Precompilers 

Using SASS or LESS and still want to have the benefits of live CSS reloading?

Simply run your sass or less watcher/compiler on the command line and
make sure the final output CSS files land in one of the directories
that you have listed in your `:css-dirs` configuration option (mentioned above).

See [lein-cooper](https://github.com/kouphax/lein-cooper) for a
familiar way to launch processes from lein.


## Client side configuration options 

Instead of setting `:figwheel true` in your cljsbuild configuration
you can pass a map of options as below:

```clojure
:cljsbuild {
  :builds [ { :id "example" 
              :source-paths ["src/"]

              ;; put client config options in :figwheel
              :figwheel { :websocket-host "localhost" 
                          :on-jsload "example.core/fig-reload"}
                          
              :compiler {  :main "example.core"
                           :asset-path "js/out"
                           :output-to "resources/public/js/example.js"
                           :output-dir "resources/public/js/out"
                           :optimizations :none } } ]
}
```

The following configuration options are available:

```clojure

;; Configure :websocket-host for the figwheel js client to connect to.
;; (Don't specify the port; figwheel already knows it).
;; Defaults to "localhost".  Valid values are:
;; 
;;   <any-string>      Uses that exact string as hostname.
;;
;;   :js-client-host   Uses window.location.hostname from JS.  This is useful when connecting
;;                     from a different device/computer on your LAN, e.g. testing mobile
;;                     safari.
;;
;;   :server-ip        Uses the IP address of the figwheel server.  This is Useful in special
;;                     situations like an iOS (WK)WebView.  Be sure to check your CORS headers.
;;
;;   :server-hostname  Like :server-ip, but uses hostname string rather than IP address.
;;                     (On unix, check that `hostname` outputs the right string in shell).
;;
:websocket-host :js-client-host

;; optional callback
:on-jsload "example.core/fig-reload"

;; if you want to do REPL based development and not have
;; have compiled files autoloaded into the client env
:autoload false

;; The heads up display is enabled by default; to disable it: 
:heads-up-display false

;; when the compiler emits warnings figwheel blocks the loading of files.
;; To disable this behavior:
:load-warninged-code true

;; You can override the websocket url that is used by the figwheel client
;; by specifying a :websocket-url
;;
;; The value of :websocket-url is usually
;; :websocket-url "ws://localhost:3449/figwheel-ws"
;;
;; The :websocket-url is normally derived from the :websocket-host option.
;; If you supply a :websocket-url the :websocket-host option will be ignored.
;;
;; The :websocket-url allows you to use tags for common dynamic values.
;; For example in:
;; :websocket-url "ws://[[client-hostname]]:[[server-port]]/figwheel-ws"
;; Figwheel will fill in the [[client-hostname]] and [[server-port]] tags
;;
;; Available tags are
;; [[server-hostname]]
;; [[server-ip]]
;; [[server-port]]
;; [[client-hostname]]
;; [[client-port]]
```

### More Figwheel Configuration Information

All Figwheel configuration options are fully specified in 
[sidecar/src/figwheel_sidecar/schemas/config.clj](https://github.com/bhauman/lein-figwheel/blob/master/sidecar/src/figwheel_sidecar/schemas/config.clj). 

This is currently the ultimate configuration reference. (I'm planning on generating 
an official config reference from this file.)

### Preventing and forcing file reloads

Figwheel normally reloads any file that has changed. If you want to
prevent certain files from being **reloaded** by figwheel, you can add
meta-data to the namespace declaration like so:

```clojure
(ns ^:figwheel-no-load example.core)
```

Figwheel will not load or reload files that haven't been required by
your application. If you want to force a file to be loaded when it
changes add the follwoing meta-data the namespace declaration of the file:

```clojure
(ns ^:figwheel-load example.core)
```

It can be very helpful to have a file reload every time a file changes
in your ClojureScript source tree. This can facilitate reloading your
main app and running tests on change.

To force a file to reload on every change:

```clojure
(ns ^:figwheel-always example.test-runner)
```


#### Using the ClojureScript REPL

When you run `lein figwheel` a REPL will be launched into your application.

You will need to open your application in a browser in order for the
REPL to connect and show its prompt.

This REPL is a little different than other REPLs in that it has live
compile information from the build process. This effectively means
that you will not have to call `(require` or `(load-namespace` unless
it is a namespace that isn't in your loaded application's required
dependencies. In many cases you can just `(in-ns 'my.namespace)` and
everything you need to access will be there already.

The REPL get's its syntax highlighting and other features from the
[rebel-readline](https://github.com/bhauman/rebel-readline) library.

You can type `:repl/help` to learn more about how to use it.

> For Windows: Rebel-readline will not be automatically included on
> windows you will have to use `lein trampoline figwheel` in order to
> get rebel-readline. And this should be done with the knowledge that
> the classpath may be corrupted if it gets too long and thus things
> will stop working.
> See [Scripting Figwheel](#scripting-figwheel) below if you want to use
> rebel-readline in a stable manner



##### REPL Figwheel control functions.

The Figwheel REPL has the following control functions:

```
Figwheel Controls:
 (stop-autobuild)            ;; stops Figwheel autobuilder
 (start-autobuild [id ...])  ;; starts autobuilder focused on optional ids
 (switch-to-build id ...)    ;; switches autobuilder to different build
 (reset-autobuild)           ;; stops, cleans, and starts autobuilder
 (build-once [id ...])       ;; builds source one time
 (clean-builds [id ..])      ;; deletes compiled cljs target files
 (fig-status)                ;; displays current state of system
```

These functions are special functions that poke through the
ClojureScript env into the underlying Clojure process. As such you
can't compose them.

You can think of these functions having an implicit set of build ids
that they operate on.

If you call `(reset-autobuild)` it will stop the figwheel autobuilder,
clean the builds, reload the build configuration from your
`project.clj` and then restart the autobuild process.

If you call `(stop-autobuild)` it will stop the figwheel autobuilder.

If you call `(start-autobuild)` it will start the figwheel autobuilder
with the current implicit build ids.

If you call `(start-autobuild example)` it will start the figwheel
autobuilder on the provided build id `example`. It will also make
`[example]` the implicit set of build ids.

`start-autobuild` and `switch-to-build` are the only functions that
update the build-id set.

`clean-builds` and `build-once` both allow you to do one off builds and
cleans.  They do not alter the implicit build ids.

`fig-status` displays information on the current Figwheel system state,
including whether the autobuilder is running, which build ids are in
focus, and the number of client connections.

## Editor REPLs and nREPL

You may want a REPL in your editor. This makes it much easier to ship code
from your buffer to be evaluated.

> If you use `lein repl` or something that invokes it like CIDER, you
> are using nREPL. A ClojureScript REPL will not just run over an nREPL
> connection without Piggieback.

If you are just starting out I would use the Figwheel console REPL because it's
aready set up and ready to go, complexity conquered!

If you want to integrate a REPL into your editor, here are my top
recommendations:

**Emacs**:
* use `inf-clojure` as described on the [wiki page](https://github.com/bhauman/lein-figwheel/wiki/Running-figwheel-with-Emacs-Inferior-Clojure-Interaction-Mode)
* alternatively use [Cider and nREPL](https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl). *Using the ClojureScript REPL over an nREPL connection is considered advanced*

**Cursive**: use the instructions on the [wiki page](https://github.com/bhauman/lein-figwheel/wiki/Running-figwheel-in-a-Cursive-Clojure-REPL)

**Vi**: use `tmux` mode to interact with the figwheel REPL, still trying to get a wiki page for this if you can help that would be great

If you are going to use nREPL with Figwheel please see:

[Using Figwheel within NRepl](https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl)

## Scripting Figwheel

As your development workflow grows in complexity, the declarative
approach of `lein` can be limiting when you want to launch and control
different services (ie. SASS compilation). It is really helpful to use
Clojure itself to script whatever workflow services you want.

Figwheel has a Clojure
[API](https://github.com/bhauman/lein-figwheel/blob/master/sidecar/src/figwheel_sidecar/repl_api.clj)
that makes it easy to start, stop and control Figwheel from Clojure.

In order for the following examples to work, you will need to have
`[figwheel-sidecar "0.5.18"]` and
`[com.bhauman/rebel-readline "0.1.4"]` in your dependencies.

To start Figwheel from a script, you will need to require the
`figwheel-sidecar.repl-api` and provide your build configuration to
`figwheel-sidecar.repl-api/start-figwheel!` like so:

```clojure
(require '[figwheel-sidecar.repl-api :as ra])

;; this will start figwheel and will start autocompiling the builds specified in `:builds-ids`
(ra/start-figwheel!
  {:figwheel-options {} ;; <-- figwheel server config goes here 
   :build-ids ["dev"]   ;; <-- a vector of build ids to start autobuilding
   :all-builds          ;; <-- supply your build configs here
   [{:id "dev"
     :figwheel true
     :source-paths ["src"]
     :compiler {:main "example.core"
                :asset-path "out"
                :output-to "resources/public/main.js"
                :output-dir "resources/public/out"
                :verbose true}}]})
                
;; you can also just call (ra/start-figwheel!)
;; and figwheel will do its best to get your config from the
;; project.clj or a figwheel.edn file

;; start a ClojureScript REPL
(ra/cljs-repl)
;; you can optionally supply a build id
;; (ra/cljs-repl "dev")
```

>  **Build config notes**
>
>  It's important to remember that figwheel can autobuild and reload
>  multiple builds at the same time. It can also switch between builds
>  and focus on autobuilding one at a time. For this reason you need
>  to supply the initial `:build-ids` to tell figwheel which builds
>  you want to start building. It's also really helpful to supply your
>  `:advanced` builds because while you can't autobuild them you can
>  call `build-once` on them

Assuming the above script is in `script/figwheel.clj` you can invoke it as follows:

```
$ lein trampoline run -m clojure.main script/figwheel.clj
```

The above command will start figwheel and it will behave just like
running `lein figwheel`.

Please note that the above command is not running the script in the
same environment as `lein repl` or `cider-jack-in`. Both of these
start an nREPL session. I am intentionally not using nREPL in order to
remove a lot of complexity from ClojureScript REPL communication.

> If you are using nREPL, launching the ClojureScript REPL
> requires that you have Piggieback installed. Please see the section
> above titled "Editor REPLs and nREPL"

Let's make a small helper library and then initialize a Clojure REPL with it:

```clojure
(require
 '[figwheel-sidecar.repl-api :as ra])

(defn start []
  (ra/start-figwheel!
    {:figwheel-options {} ;; <-- figwheel server config goes here 
     :build-ids ["dev"]   ;; <-- a vector of build ids to start autobuilding
     :all-builds          ;; <-- supply your build configs here
     [{:id "dev"
       :figwheel true
       :source-paths ["src"]
       :compiler {:main "example.core"
                  :asset-path "out"
                  :output-to "resources/public/main.js"
                  :output-dir "resources/public/out"
                  :verbose true}}]}))

;; Please note that when you stop the Figwheel Server http-kit throws
;; a java.util.concurrent.RejectedExecutionException, this is expected

(defn stop []
  (ra/stop-figwheel!))

(defn repl []
  (ra/cljs-repl))
```

The next line will call `clojure.main` and initialize it with our
script and then continue on to launch a REPL.

```
$ lein trampoline run -m clojure.main --init script/figwheel.clj -m rebel-readline.main
```

After the Clojure REPL has launched, you will now have the ability to
call `(start)`, `(repl)` and `(stop)` as you need.

You can also call all of the functions in the [figwheel-sidecar.repl-api](https://github.com/bhauman/lein-figwheel/blob/master/sidecar/src/figwheel_sidecar/repl_api.clj).

This is a powerful way to work, as you now have the interactivity and
generality of the Clojure programming language available.

Need to start a server? Go for it.<br/>Need to watch and compile SASS files? No problem.

### Tips and Support

Figwheel was created out of the pure desire to make programming more
fun. While I have been lucky to receive a couple spontaneous donations,
it is not currently sponsored in any way.

If you like Figwheel and want to support its development:

<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=B8B3LKTXKV69C">
<img src="https://s3.amazonaws.com/bhauman-blog-images/Smaller%2BDonate%2BButton%402x.png" width="200">
</a>

### Not Magic, just plain old file reloading 

This plugin starts a ClojureScript auto builder, opens a websocket and
starts static file server. When you save a ClojureScript file,
Figwheel will detect that and compile it and other affected files. It
will then pass a list of those changed files off to the figwheel
server. The figwheel server will in turn push the paths of the
**relevant** compiled javascript files through a websocket so that the
browser can reload them.

The main motivation for lein-figwheel is to allow for the interactive
development of ClojureScript. Figwheel doesn't provide this out of the
box, **the developer has to take care to make their code reloadable**. 

## Writing reloadable code

Figwheel relies on having files that can be reloaded. 

Reloading works beautifully on referentially transparent code and
code that only defines behavior without bundling state with the
behavior. 

If you are using React or Om it's not hard to write reloadable code,
in fact you might be doing it already.

There are several coding patterns to look out for when writing
reloadable code. 

One problematic pattern is top level definitions that have local
state.

```clojure
(def state (atom {}))
```

The `state` definition above is holding an atom that has local state.
Every time the file that holds this definition gets reloaded the state
definition will be redefined and the state it holds will be reset back
to the original state. But with figwheel we are wanting to change our
programs while maintaining the state of the running program.

The way to fix this is to use `defonce`

```clojure
(defonce state (atom {}))
```

This will fix most situations where you have code that is relying on a
definition that has local state. Keep in mind though that if you
change the code that is wrapped in a `defonce` you won't see the
changes, because the identifier won't be redefined.

Complicated object networks wired together with callbacks (Backbone,
Ember, etc.) are also problematic. Instantiating these object callback
networks and then storing them in a global var is yet another version
of this problem.

Functions that maintain local state like counters and such are also
definitions with local state, and as such are problematic.

You also need to look out for common setup code that hooks into the browser.

Often you will see statements like this at the bottom of a file.

```clojure
(.click ($ "a.button") (fn [e] (print "clicked button")))
```

Every time this file gets loaded a new listener will get added to all
the anchor tags with a "button" class. This is obviously not what we
want to happen.

This code is very problematic and points to the why using the browser
APIs directly has always been really difficult. For instance if we make
it so that these hooks are only executed once, like so:

```clojure
(defonce setup-stuff 
  (do 
     (.click ($ "a.button") (fn [e] (print "clicked button")))))
```

When you are live editing code, this doesn't work very well. If you
alter your HTML template any new "a.button" elements aren't going to
have the listener bound to them.

You can fix this by using an event delegation strategy as so:

```clojure  
(defonce setup-stuff 
  (do 
     (.on ($ "div#app") "click" "a.button" (fn [e] (print "clicked button")))))
```

But even with the above strategy you won't be able to edit any of the
code in the setup up block and see your changes take effect.

If you are not using React and you want to build things this way and
have reloadable code we need to create `setup` and `teardown`
functions to be invoked on code reload.  

```clojure  
(defn setup []
   (.on ($ "div#app") "click" "a.button" (fn [e] (print "clicked button"))))

(defn teardown []
   (.off ($ "div#app") "click" "a.button"))

;; define a :on-jsload hook in your :cljsbuild options
(defn fig-reload-hook []
      (teardown)
      (setup))

```

Now you can edit the code in the setup and teardown functions and see
the resulting changes in your application.

In a way you can think of the previous definitions of `setup-stuff` as
functions that have local state of sorts. They are altering and storing
callbacks in the DOM directly and this is why it is so problematic.

This is one of the reasons React is so damn brilliant. You never end
up storing things directly in the DOM. State is mediated and managed
for you. You just describe what should be there and then React takes
care of making the appropriate changes. For this reason React is a
prime candidate for writing reloadable code. React components already
have a lifecycle protocol that embeds `setup` and `teardown` in each
component and invokes them when neccessary.

It is worth repeating that React components don't have local state, it
just looks like they do. You have to ask for the local state and React in
turn looks this state up in a larger state context and returns it,
very similar to a State Monad.

Reloadable code is easy to write if we are very conscious and careful
about the storage of state, state transitions and side effects. Since
a great deal of programming complexity stems from complex interactions
(side effecting events) between things that have local state, it is my
belief that reloadable code is often simply better code.

## License

Copyright © 2018 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
