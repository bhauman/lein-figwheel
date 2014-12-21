# lein-figwheel

Figwheel builds your ClojureScript code and hots loads it into the browser as you are coding!

Here is a [live demo of figwheel](https://www.youtube.com/watch?v=KZjFVdU8VLI)

See the introductory blog post [here](http://rigsomelight.com/2014/05/01/interactive-programming-flappy-bird-clojurescript.html).

Current version: [lein-figwheel "0.2.0-SNAPSHOT"](https://clojars.org/lein-figwheel)

![Figwheel heads up example](https://s3.amazonaws.com/bhauman-blog-images/figwheel_image.png)

## Features 

#### Live code reloading

If you write [**reloadable code**](https://github.com/bhauman/lein-figwheel#writing-reloadable-code), figwheel can facilitate automated live
interactive programming. Every time you save your ClojureScript source
file the changes are sent to the browser so you can see the effects of
modifying your code in real time.  This is different than interactive
programming in the browser-repl where you need to cherry pick which
changes to send and which processes to start, etc.

#### Static file server

The inclusion of a **static file server** allows you to get a decent
ClojureScript development environment up and running quickly. For
convenience there is a `:ring-handler` option so you can load a ring
handler into the figwheel server.

#### Live CSS reloading

Figwheel will reload your CSS live as well.

#### Heads up display

Figwheel has a non-intrusive heads up display that gives you feedback
on how well your project is compiling. By writing a shell script you
can click on files in the heads up display and they will open in your
editor!

#### Robust connection

Figwheel's connection is fairly robust. I have experienced figwheel
sessions that have lasted for days through multiple OS sleeps. You can
also use figwheel like a Repl if you are OK with using `print` to output
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
won't load it. This again if very helpful in keeping the client
environment stable. This behavior is optional and can be turned off.

## Quick Start

Make sure you have the [latest version of leinigen installed](https://github.com/technomancy/leiningen#installation).

You can try figwheel out quickly with the flappy bird demo:

    git clone https://github.com/bhauman/flappy-bird-demo.git

then cd into `flappy-bird-demo` and type

    lein figwheel

You can now goto `localhost:3449/index.html` and open up
`src/flappy_bird_demo/core.cljs` with your favorite editor and start
coding. Make sure you open your browser's development console so you
can get feedback about code reloads.

If you would prefer to greenfield a new project you can use the
figwheel leinigen template.

    lein new figwheel hello-world

## Usage

First make sure you include the following `:dependencies` in your `project.clj` file.

```clojure
[org.clojure/clojurescript "0.0-2496"] ;; has to be at least 2202 or greater
[figwheel "0.2.0-SNAPSHOT"]            ;; needed for figwheel client
```

Then include `lein-figwheel` along with `lein-cljsbuild` in the `:plugins`
section of your project.clj.

```clojure
[lein-cljsbuild "1.0.3"] ;; 1.0.3 is a requirement
[lein-figwheel "0.2.0-SNAPSHOT"]
```

#### Configure lein cljsbuild

You also need to have your `lein-cljsbuild` configuration set up in your
`project.clj`.

Here is an example:

```clojure
:cljsbuild {
  :builds [ { :id "example" 
              :source-paths ["src/"]
              :compiler { :output-to "resources/public/js/compiled/example.js"
                          :output-dir "resources/public/js/compiled/out"
                          :externs ["resources/public/js/externs/jquery-1.9.js"]
                          :optimizations :none
                          :source-map true } } ]
}
```

The important part here is that you have to have at least one `build`
and that build has to have `:optimizations` set to `:none`.

The output directory has to be in a directory that can be served by
the static webserver. The default for the webserver root is
"resources/public" so your output files need to be in a subdirectory
"resources/public" unless you change the webserver root. For now the
webserver root has to be in a subdirectory of `resources`.

Start the figwheel server. (This will get the first optimizations
none build)

    $ lein figwheel

or optionally give the name of the build

    $ lein figwheel example

This will start a server at `http://localhost:3449` with your
resources being served via the compojure `resources` ring handler.

So you can load the a html file thats hosting your ClojureScript app
by going to `http://localhost:3449/<yourfilename>.html`

[Cljsbuild has many many more options](https://github.com/emezeske/lein-cljsbuild/blob/master/sample.project.clj)

### Server configuration

In your `project.clj` you can add the following configuration parameters:

```clojure
:figwheel {
   :http-server-root "public" ;; this will be in resources/
   :server-port 3449          ;; default

   ;; CSS reloading (optional)
   ;; :css-dirs has no default value 
   ;; if :css-dirs is set figwheel will detect css file changes and
   ;; send them to the browser
   :css-dirs ["resources/public/css"]

   ;; Server Ring Handler (optional)
   ;; if you want to embed a ring handler into the figwheel http-kit
   ;; server
   :ring-handler example.server/handler

   ;; To be able to open files in your editor from the heads up display
   ;; you will need to put a script on your path.
   ;; that script will have to take a file path and a line number
   ;; ie. in  ~/bin/myfile-opener
   ;; #! /bin/sh
   ;; emacsclient -n +$2 $1
   ;;
   :open-file-command "myfile-opener"
} 
```

## Client side usage

In your project.clj you need to include figwheel in your dependencies.

```clojure
[figwheel "0.2.0-SNAPSHOT"]
```

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
    <script src="js/compiled/out/goog/base.js" type="text/javascript"></script>
    <script src="js/compiled/example.js" type="text/javascript"></script>
    <script type="text/javascript">goog.require("example.core");</script>
  </body>
</html>
```

In keeping with the previous examples you would put this into your
`src/example/core.cljs`:

```clojure
(ns example.core
  (:require
   [figwheel.client :as fw]))

(enable-console-print!)

(println "You can change this line an see the changes in the dev console")

(fw/start {
  ;; configure a websocket url if yor are using your own server
  ;; :websocket-url "ws://localhost:3449/figwheel-ws"

  ;; optional callback
  :on-jsload (fn [] (print "reloaded"))

  ;; The heads up display is enabled by default
  ;; to disable it: 
  ;; :heads-up-display false

  ;; when the compiler emits warnings figwheel
  ;; blocks the loading of files.
  ;; To disable this behavior:
  ;; :load-warninged-code true
})
```

The call to `start` is idempotent and can be called many
times safely. 

Whole files will be reloaded on change so we have to make sure that
we [write reloadable code](https://github.com/bhauman/lein-figwheel#writing-reloadable-code).

Please check out the example project in the `example` directory.

To see all the client side config options [look here](https://github.com/bhauman/lein-figwheel/blob/master/support/src/figwheel/client.cljs#L178).

### Using your own server

You do not have to use the figwheel server to host your app and its
static assets. You can use your own server. 

To use your own server simply navigate to your server url for the page
that is hosting your ClojureScript app.

In this case, you have to let the figwheel client know where figwheel
websocket is.

Like so:

```clojure
(fw/start {
  :websocket-url   "ws://localhost:3449/figwheel-ws"
  :on-jsload (fn [] (print "reloaded"))
})
```

Note that you will still need to run the figwheel server in addition to 
your development app server if you wish to continue utilizing figwheel.

For example, you could run figwheel in one terminal...

```
$ lein figwheel
```

and run your app server of choice in another...

```
$ lein ring server
```

#### Mapping figwheel resource paths to your servers resource paths

Somethings you want to load __js__ and __css__ resources from your
server and your server paths are different than Figwheel's paths.

You can use the `:url-rewriter` client option to rewrite resource
request urls. The `:url-rewriter` config options takes a function that
recieves the resource url and should return a corrected url that
points to the same resource on your server.

```clojure
(fw/start {
  :websocket-url   "ws://localhost:3449/figwheel-ws"
  :url-rewriter    (fn [url] (clojure.string/replace url ":3449" ":3000"))
})
```

## Resources 

[Figwheel keep om turning](http://blog.michielborkent.nl/blog/2014/09/25/figwheel-keep-Om-turning/) is an excellent blog post on how to use figwheel with Om.  It's also worth reading if you aren't using Om.

[Chestnut](https://github.com/plexus/chestnut) is a very complete
leiningen template that includes figwheel.

### What actually happens

This plugin starts the cljsbuild auto builder, opens a websocket and
starts static file server. When you save a ClojureScript file,
cljsbuild will detect that and compile it and other affected files. It
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
code in the setup up block and see your changes take affect.

If you are not using React and you want to build things this way and
have reloadable code we need to create `setup` and `teardown`
functions to be invoked on code reload.  

```clojure  
(defn setup []
   (.on ($ "div#app") "click" "a.button" (fn [e] (print "clicked button"))))

(defn teardown []
   (.off ($ "div#app") "click" "a.button")

;; hook in the  
(fw/start {
  :on-jsload (fn [] 
               (teardown)
               (setup))
})
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


## More React Advocacy

If you want to do less thinking and write more reliable front end code
you should really be looking at React, Om etc.

OK enough.

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
