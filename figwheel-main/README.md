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

> Currently still undergoing heavy development. Stuff will most certainly change.

> This documentation is incomplete and intended to help you take the new figwheel
> for a spin before its official release.

## Quick Usage

First, make sure you have the [Clojure CLI Tools](https://clojure.org/guides/getting_started) 
installed.

Now quickly launch a ClojureScript REPL with:

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
clojure -Sdeps "{:deps {com.bhauman/figwheel-main {:mvn/version \"0.1.0-SNAPSHOT\"} com.bhauman/rebel-readline-cljs {:mvn/version \"0.1.2\"}}}}"  -m figwheel.main
```

As of right now Rebel readline does create some startup overhead
(hoping to correct this in the near future), so you may want to choose
use it only when you are going to interact at the REPL.

**Creating a build**

To define a build which will allow you work on a set of files and hot
reload them.

Create a file `dev.cljs.edn`:

```
{:main example.core}
```

And in `src/example/core.cljs`

```
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

## Configuring Figwheel Main

If you need to configure figwheel.main, place a `figwheel-main.edn`
in the same directory that you will be executing it from.

If you need to override some of the configuration options for a
particular build, simply add those options as meta data on the build edn.

For example if you want to have `:watch-dirs` that are specific to the
"dev" build then in `dev.cljs.edn`

```
^{:watch-dirs ["cljs-src"]
  :css-dirs ["resources/public/css"]}
{:main example.core}
```

All the available configuration options are documented here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/doc/figwheel-main-options.md

All the available configuration options specs are here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/src/figwheel/main/schema.clj

## More to come ...

Figwheel Main aims to honor all the flags provided by `cljs.main` as
of right now your mileage may vary.

## Known issues

* Not working with Node yet
* Quiting from rebel-readline REPL requires quiting multiple processes

## License

Copyright © 2018 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
