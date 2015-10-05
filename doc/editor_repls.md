# Editor REPLs, Figwheel, ClojureScript and nREPL

If you are just wanting an nREPL connection into the underlying
figwheel process for Clojure development please skip ahead to
[Connecting to the Figwheel process with an nREPL
client](#connecting-to-the-figwheel-process-with-an-nrepl-client)


You may want a ClojureScript REPL in your editor. This makes it much
easier to ship code from your buffer to be evaluated.

But ... we need a little context before you start on this journey.

The ClojureScript REPL has much more inherent complexity than the
Clojure REPL. The ClojureScript REPL needs to compile code to
JavaScript and then ship it off to an evaluation environment.

Folks who have been using ClojureScript for a while initially had to
deal with a REPL that didn't work very well, and from this perspective
are grateful for a REPL that just starts up and stays connected to the
evaluation environment.

Folks who are new to ClojureScript and are familiar with the Clojure
REPL workflow, often expect to reproduce this workflow in ClojureScript
and are often quickly disappointed by the ClojureScript REPL.

nREPL is the defacto REPL for remote Clojure REPL connections and
while this works great for Clojure, ClojureScript support is a
different story. Booting a ClojureScript REPL on top of an nREPL
session is [not an easy
task](https://github.com/cemerick/piggieback/blob/master/src/cemerick/piggieback.clj)
and adds further complexity to an already complex story. This currently doesn't work very
well as evidenced by many failures in various software version
combinations.

**So be wary of setting up an nREPL workflow for CLJS.** Do not expect
it to be easy or to just work.

At this time I do not reccomend nREPL for CLJS development unless you
have a lot of experience with all the moving parts of CLJS, nREPL etc.

#### My Editor REPL recommendations

If you are just starting out I would use the Figwheel console REPL because it's
aready set up and ready to go, complexity conquered!

If you want a REPL in your editor here are my top recommendations:

**Emacs**:   use `inf-clojure` as described on the [wiki page](https://github.com/bhauman/lein-figwheel/wiki/Running-figwheel-with-Emacs-Inferior-Clojure-Interaction-Mode)

**Cursive**: use the instructions on the [wiki page](https://github.com/bhauman/lein-figwheel/wiki/Running-figwheel-in-a-Cursive-Clojure-REPL)

**Vi**:      use `tmux` mode to interact with the figwheel REPL, still trying to get a wiki page for this if you can help that would be great

All of the above options use the figwheel REPL without nREPL.

#### Connecting to the Figwheel process with an nREPL client

Leveraging the figwheel process for an nREPL connection can help in several ways:

* reduce the number of JVM processes that are running on your machine
* allows one to work on your server code interactively if you are using figwheel's `:ring-handler` option
* allows you to work on `figwheel-sidecar` code interactively
* boot a ClojureScript REPL over the nREPL connection (not currently reccomended)

To have figwheel launch an nREPL server you will need to add the `:nrepl-port` option to the
`:figwheel` config in your `project.clj`
```
:figwheel {
 ;; Start an nREPL server into the running figwheel process
 :nrepl-port 7888
}
```

##### nREPL Middleware

There are several tools for developing/editing Clojure and
ClojureScript that rely on nREPL middleware.

Figwheel used to depend on and include the CIDER and Piggieback
middleware. As of figwheel version **0.4.0** this is no longer the
case. By default figwheel will only try to load the Piggieback
middleware, if it is in your dependencies.

Though the CIDER middleware has been removed from figwheel's default
dependencies, it is now possible to specify which nREPL middleware you
want figwheel to load. Of course you have to make sure all the
middleware is available on the classpath (dependencies/plugins).

You can configure the middleware to load by adding the `:nrepl-middleware`
option to the `:figwheel` config in `project.clj`
```
:figwheel {
  ;; Start an nREPL server into the running figwheel process
  :nrepl-port 7888
  
  ;; Load CIDER, refactor-nrepl and piggieback middleware
  :nrepl-middleware ["cider.nrepl/cider-middleware"
                     "refactor-nrepl.middleware/wrap-refactor"
                     "cemerick.piggieback/wrap-cljs-repl"]
}
```

This option will override the default inclusion of Piggieback
middleware so make sure you add the Piggieback middleware as well.

IF you want to run the nREPL without any middleware you can just
provide an empty vector.
```
:nrepl-middleware []
```

Now when you start figwheel `lein figwheel` the nREPL server will be
started with your preferred middleware.

##### Piggieback, nREPL support for the CLJS REPL

If you want to use the CLJS REPL over an nREPL connection you are
going to need [Piggieback](https://github.com/cemerick/piggieback)

As of version **0.4.0** figwheel no longer has a hard dependency on
Piggieback. It will still try to load the Piggieback repl when you have
an nREPL connection open, but if it isn't available it will start the
default cljs repl.

If you want to use Piggieback you'll need to add the dependency to your
project yourself.

*Note: because of the changes, figwheel no longer needs Piggieback 0.1.5.*
*You can use either Piggieback 0.1.5 or 0.2.1+.*

Example: `[com.cemerick/piggieback "0.2.1"]`






