# Editor REPLs and nREPL

You may want a REPL in your editor. This makes it much easier to ship code
from your buffer to be evaluated.

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
and adds further complexity to an already complex story.

Yes not only does a ClojureScript REPL need to compile your
expressions and ship them off to an evaluation env but it also needs
to work across an nREPL connection. This currently doesn't work very
well as evidenced by many failures in various software version
combinations.

**So be wary of setting up an nREPL workflow for CLJS.** Do not
expect it to be easy or to just work.

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

If you have to use nREPL ...


#### Connecting to figwheel with an nREPL client

If you ran `lein figwheel` and tried to connect to it with an nREPL based
client like CIDER, you will have noticed that this doesn't work by default.

To enable this you will need to add the `:nrepl-port` option to the
`:figwheel` config in your `project.clj`
```
:figwheel {
 ;; Start an nREPL server into the running figwheel process
 :nrepl-port 7888
}
```

Adding the `:nrepl-port` to the config will cause figwheel to start an 
nREPL server into the running figwheel process.

##### Piggieback

Since you're using nREPL it is likely you want to use Piggieback as well.
As of version **0.4.0** figwheel no longer has a hard dependency on
Piggieback. It will still try to load the Piggieback repl when you have
an nREPL connection open, but if it isn't available it will start the
default cljs repl.

If you want to use Piggieback you'll need to add the dependency to your
project yourself.

*Note: because of the changes, figwheel no longer needs Piggieback 0.1.5.*
*You can use either Piggieback 0.1.5 or 0.2.1+.*

Example: `[com.cemerick/piggieback "0.2.1"]`

##### Middleware

The nREPL server used to have CIDER and Piggieback middleware included.
As of figwheel version **0.4.0** this is no longer the case.
By default figwheel will only try to load the Piggieback middleware.

Though the CIDER middleware has been removed from the defaults, it is now
possible to specify which middleware you want to load, including CIDER
and refactor-nrepl. Of course you have to make sure all the middleware is
available on the classpath (dependencies/plugins).

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

This option will override the default middleware so make sure you add
the Piggieback middleware as well.

In case you want to run the nREPL without any middleware you can just
provide an empty vector.
```
:nrepl-middleware []
```

Run `lein figwheel` to start the nREPL server.

