# figwheel-main

Figwheel Main is intended to provide a `cljs.main` like command line
experience for ClojureScript that also provides the many features that
were first developed in `lein-figwheel` but better.

* Hot Code Reloading
* Stable best of class REPL connection
* CSS reloading
* Heads-up display for compile time errors
* Pop to editor from heads-up display
* Built in ring development server

`figwheel-main` is a complete rewrite of original figwheel. All of the
above features have been improved significantly.

* Hot code reloading has been significantly revamped.
* The REPL now only evals on one client and allows you to choose which one at runtime.
* The built-in ring server is now the ring-jetty-adapter which will
  allow the use of HTTPS, and extensive configuration of the server itself
* the built-in ring server now uses `ring-defaults` and allows
  extensive configuration of the middleware
* the amount of config needed to get started has been significantly
  reduced
* the configuration options have been simplified

The new architecture also makes it trivial to add your own development
tools that can communicate from the server to your client.

> Currently ONLY works for a browser environment, Node support will be
> coming next with other targets soon following.

> Currently still undergoing heavy development. Stuff will most certainly change.

> This documentation is incomplete and intended to help you take the new figwheel
> for a spin before its official release.

## Quick Usage

> It is assumed that you have perused https://clojurescript.org/guides/quick-start

First, make sure you have the [Clojure CLI Tools](https://clojure.org/guides/getting_started) 
installed.

On Mac OSX with brew:

    brew install clojure

Now launch a ClojureScript REPL with:

```
clj -Sdeps "{:deps {com.bhauman/figwheel-main {:mvn/version \"0.1.0-SNAPSHOT\"}}}}"  -m figwheel.main
```

This will first compile browser REPL code to a temp directory, and
then a browser will open and a `cljs.user=>` prompt will appear.

From here you can do REPL driven development of ClojureScript.

You can also use `leiningen` by adding it to `:dependencies` in your
`project.clj` and launching it like so:

```
lein run -m figwheel.main
```

**With Rebel Readline for much better REPL experience**

Figwheel main will automatically use `rebel-readline-cljs` it is
available. So you can get Rebel Readline behavior by simply adding it
to your dependencies.

```
clojure -Sdeps "{:deps {com.bhauman/figwheel-main {:mvn/version \"0.1.0-SNAPSHOT\"} com.bhauman/rebel-readline-cljs {:mvn/version \"0.1.3\"}}}}"  -m figwheel.main
```

As of right now Rebel readline does create some startup overhead
(hoping to correct this in the near future), so you may want to choose
use it only when you are going to interact at the REPL.

**Creating a build**

To define a build which will allow you work on a set of files and hot
reload them.

Ensure your `deps.edn` file has `figwheel.main` dependencies:

```clojure
{:deps {com.bhauman/figwheel-main {:mvn/version "0.1.0-SNAPSHOT"}
        com.bhauman/rebel-readline-cljs {:mvn/version "0.1.3"}}
 ;; setup common development paths that you may be used to 
 ;; from lein
 :paths ["src" "target" "resources"]}
```

Create a file `dev.cljs.edn` build file:

```clojure
{:main example.core}
```

And in `src/example/core.cljs`

```clojure
(ns example.core)
(enable-console-print!)
(prn "hello world!")
```

and run the command:

```
clojure -m figwheel.main -b dev -r
```

This will launch a REPL and start autobuilding and reloading the `src`
directory so that any files you add or change in that directory will
be automatically hot reloaded into the browser.

The `-b` or `--build` flag is indicating that we should read
`dev.cljs.edn` for configuration.

The `-r` or `--repl` flag indicates that a repl should be launched.

Interesting to note that the above command is equivalent to:

```
clojure -m figwheel.main -co dev.cljs.edn -c example.core -r
```

Note: that if you want to add your own `index.html` file to host your
application, if you have added `resources` to your "deps.edn" `:paths`
key, as demonstrated above, you can place the `index.html` in
`resources/public/index.html`

## Configuring Figwheel Main

If you need to configure figwheel.main, place a `figwheel-main.edn`
in the same directory that you will be executing it from.

If you need to override some of the figwheel configuration options for a
particular build, simply add those options as meta data on the build edn.

For example if you want to have `:watch-dirs` that are specific to the
"dev" build then in `dev.cljs.edn`

```clojure
^{:watch-dirs ["cljs-src"]
  :css-dirs ["resources/public/css"]}
{:main example.core}
```

All the available configuration options are documented here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/doc/figwheel-main-options.md

All the available configuration options specs are here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/src/figwheel/main/schema.clj

## Classpaths, Classpaths, Classpaths

Understanding of the Java Classpath can be very helpful when working
with ClojureScript. 

ClojureScript searches for source files on the Classpath. When you add
a `re-frame` dependency like so:

```clojure
{:deps {com.bhauman/figwheel-main {:mvn/version "0.1.0-SNAPSHOT"}
        com.bhauman/rebel-readline-cljs {:mvn/version "0.1.3"}
        ;; adding re-frame
        re-frame {:mvn/version "1.10.5"}}
 :paths ["src" "target" "resources"]}
```

The source files in `re-frame` are on the Classpath and the
ClojureScript compiler can find `re-frame.core` when you require it.

Your sources will need to be on the Classpath so that the Compiler can
find them. For example, if you have a file
`cljs-src/example/core.cljs` you should add `cljs-src` to the `:paths`
key so that the ClojureScript compiler can find your `example.core`
namespace. It is important to note that the `src` directory is on your
Classpath by default.

In Figwheel, the embedded HTTP server serves its files from the Java
Classpath.

It actually serves any file it finds in on a Classpath in a `public`
sub-directory. This is why we added `target` and `resources` to the
`:paths` key in the `deps.edn` file above. With `target` and
`resources` both on the Classpath the server will be able to serve
anyfile in `target/public` and `resources/public`.

The compiler by default compiles artifacts to `target` for easy cleaning.

It is custmary to put your `index.html`, CSS files, and other
web artifacts in the `resources/public` directory.

## Working with Node.js

Unlike `cljs.main`, with `figwheel.main` you will not specify a
`--repl-env node` because the `figwheel.repl` handles Node.js REPL
connections in addition to others.

You can launch a Node REPL like so:

    clojure -m figwheel.main -t node -r
    
You can quickly get a hot reloading CLJS node build up an running using the
`deps.edn`, `example.core` and `dev.cljs.edn` above. Simply add a `--target node`
or `-t node` to the compile command.

    clojure -m figwheel.main -t node -b dev -r

This will launch a CLJS Node REPL initialized with `example.core` you
can now edit `example/core.cljs` and it will be hot reloaded.
    
Of course if you add `:target :nodejs` to `dev.cljs.edn` like so:

```clojure
{:main example.core
 :target :nodejs}
```

You be able to run the build more simply:

    clojure -m figwheel.main -t node -b dev -r

## Reload hooks

It is common to want to provide callbacks to do some housekeeping
before or after a hot reload has occurred.

You can conveniently configure hot reload callbacks at runtime with
metadata. You can see and example of providing callbacks below:

```clojure
;; first notify figwheel that this ns has callback defined in it
(ns ^:figwheel-hooks example.core)

;; mark the hook functions with ^:before-load and ^:after-load 
;; metadata

(defn ^:before-load my-before-reload-callback []
    (println "BEFORE reload!!!"))

(defn ^:after-load my-after-reload-callback []
    (println "AFTER reload!!!"))
```

The reload hooks will be called before and after every hot code reload.

## Quick way for experienced devs to understand the command line options

You can supply a `-pc` or `--pprint-config` flag to `figwheel.main`
and it will print out the computed configuration instead of running
the command.

For example:

```
clojure -m figwheel.main -pc -b dev -r
```

Will output:

```clojure
---------------------- Figwheel options ----------------------
{:ring-handler #'exproj.server/handler,
 :ring-server-options {:port 9550},
 :client-print-to [:repl :console],
 :pprint-config true,
 :watch-dirs ("src"),
 :mode :repl}
---------------------- Compiler options ----------------------
{:main exproj.core,
 :preloads [figwheel.core figwheel.main figwheel.repl.preload],
 :output-to "target/public/cljs-out/dev-main.js",
 :output-dir "target/public/cljs-out/dev",
 :asset-path "cljs-out/dev",
 :aot-cache false,
 :closure-defines
 #:figwheel.repl{connect-url
                 "ws://localhost:9550/figwheel-connect?fwprocess=c8712b&fwbuild=dev",
                 print-output "repl,console"}}
```

## Using figwheel.main from a script

See the `figwheel.main/start` function and the `figwheel.main/start-join` functions.

## More to come ...

Figwheel Main aims to honor all the flags provided by `cljs.main` as
of right now your mileage may vary.

## Known issues

* Quiting from rebel-readline REPL requires quiting multiple processes

## License

Copyright © 2018 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
