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
time features that can communicate from the server to your client.

> Currently still undergoing heavy development and things will change.

> This documentation is incomplete and intended to help you take the new figwheel
> for a spin before its official release.

## Quick Usage

First, make sure you have the [Clojure CLI Tools](https://clojure.org/guides/getting_started) 
installed.

In a project directory that you want to start working with figwheel in
create a `deps.edn` file.

```
{:deps {com.bhauman/figwheel-main {:mvn/version "0.1.0-SNAPSHOT"}
        ;; add rebel readline for a better REPL readline editor
        com.bhauman/rebel-readline-cljs {:mvn/version "0.1.2"}}
 :paths ["src" "target"]}
```

The above defines our dependencies and adds the `src` and `target`
directories to the classpath. We need the `target` directory on the
classpath so that our compiled assets are accessible to the server
(this is configurable).

Now launch a REPL with

```
clojure -m figwheel.main
```

This will launch open a browser window and a REPL connected to it.

From here you can do REPL driven development of ClojureScript.

You can also use `leiningen` like so:

```
lein trampoline run -m figwheel.main
```

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

## More to come ...

Figwheel Main aims to honor all the flags provided by `cljs.main` as
of right now your mileage may vary.

## License

Copyright © 2018 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
