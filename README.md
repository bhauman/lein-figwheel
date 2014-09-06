# lein-figwheel

File Eval Gui Loop > FEGL > FEGUIL > "figwheel"

![Fig wheel on a drink](http://s3.amazonaws.com/bhauman-blog-images/Fig-Sidecar_Pomegranate-Bistro1-1.jpg)

#### Still a work in progress. But that doesn't mean it's not a cool garnish.

A Leiningen plugin that builds your ClojureScript and pushes the
changes to the browser.

See the introductory blog post [here](http://rigsomelight.com/2014/05/01/interactive-programming-flappy-bird-clojurescript.html).

If you write reloadable code, figwheel can facilitate automated live
interactive programming. Every time you save your ClojureScript source
file the changes are sent to the browser so you can see the effects of
modifying your code in real time.  This is different than interactive
programming in the browser-repl where you need to cherry pick which
changes to send and which processes to start, etc.

The inclusion of a **static file server** allows you to get a decent
ClojureScript development environment up and running quickly.

Figwheel's connection is fairly robust. I have experienced figwheel
sessions that have lasted for days through multiple OS sleeps. You can
also use figwheel like a Repl if you are OK with using `print` to output
the evaluation results to the browser console.

Figwheel also has **live CSS reloading**.

Figwheel **broadcasts** changes to all connected clients. This means you
can see code and CSS changes take place in real time on your phone and
in your laptop browser simultaneously. The broadcast of live code
updates can have interesting applications. You could have a whole
classroom directly interacting with a game that is being worked on
live from the front of the room or even remotely.

## Demo

Here is a [live demo of figwheel](https://www.youtube.com/watch?v=KZjFVdU8VLI)

### What actually happens

This plugin starts the cljsbuild auto builder, opens a websocket and
starts static file server. When you save a ClojureScript file,
cljsbuild will detect that and compile it and other affected files. It
will then pass a list those changed files off to the figwheel server.
The figwheel server will in turn push the paths of the **relevant**
compiled javascript files through a websocket so that the browser can
reload them.

There is also a figwheel client that you need to include into your
ClojureScript project to start a process which listens for changes and
reloads the files.

The main motivation for lein-figwheel is to allow for the interactive
development of ClojureScript. Figwheel doesn't provide this out of the
box, **the developer has to take care to make their code reloadable**. 

If you are using React or Om it's not hard to write reloadable code,
in fact you might already be doing it.

## Quick Start

Make sure you have the [latest version of leinigen installed](https://github.com/technomancy/leiningen#installation).

You can get started quickly with the flappy bird demo:

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

#### For ClojureScript > 0.0-2197

First make sure you include the following `:dependencies` in your `project.clj` file.

```clojure
[org.clojure/clojurescript "0.0-2197"] ;; has to be at least 2197 or greater
[figwheel "0.1.4-SNAPSHOT"]            ;; needed for figwheel client
```

Then include `lein-figwheel` along with `lein-cljsbuild` in the `:plugins`
section of your project.clj.

```clojure
[lein-cljsbuild "1.0.3"] ;; 1.0.3 is a requirement
[lein-figwheel "0.1.4-SNAPSHOT"]
```

#### For ClojureScript 0.0-2014 - 0.0-2173

First make sure you include the following `:dependencies` in your `project.clj` file.

```clojure
[org.clojure/clojurescript "0.0-2173"] ;; has to be between 2014 - 2197
[figwheel "0.1.2-2173-SNAPSHOT"]            ;; needed for figwheel client
```

Then include `lein-figwheel` along with `lein-cljsbuild` in the `:plugins`
section of your project.clj.

```clojure
[lein-cljsbuild "1.0.2"] ;; 1.0.2 is a requirement
[lein-figwheel "0.1.2-2173-SNAPSHOT"]
```

#### Configure lein cljsbuild

You also need to have your `lein-cljsbuild` configuration set up in your
`project.clj`.

Here is an example:

```clojure
:cljsbuild {
  { :builds [ { :id "example" 
                :source-paths ["src/"]
                :compiler { :output-to "resources/public/js/compiled/example.js"
                            :output-dir "resources/public/js/compiled/out"
                            :externs ["resources/public/js/externs/jquery-1.9.js"]
                            :optimizations :none
                            :source-map true } } ] } 
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

### Server configuration

In your `project.clj` you can add the following configuration parameters:

```clojure
:figwheel {
   :http-server-root "public" ;; this will be in resources/
   :port 3449                 ;; default

   ;; CSS reloading (optional)
   ;; :css-dirs has no default value 
   ;; if :css-dirs is set figwheel will detect css file changes and
   ;; send them to the browser
   :css-dirs ["resources/public/css"]

   ;; Server Ring Handler (optional)
   ;; if you want to embed a ring handler into the figwheel http-kit
   ;; server
   :ring-handler example.server/handler 
} 
```

## Client side usage

In your project.clj you need to include figwheel in your dependencies.

```clojure
[figwheel "0.1.4-SNAPSHOT"]
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
   [figwheel.client :as fw :include-macros true]))

(enable-console-print!)

(println "You can change this line an see the changes in the dev console")

(fw/watch-and-reload
  ;; :websocket-url "ws://localhost:3449/figwheel-ws" default
  :jsload-callback (fn [] (print "reloaded"))) ;; optional callback
```

The call to `watch-and-reload` is idempotent and can be called many
times safely. 

Whole files will be reloaded on change so we have to make sure that
when we start 'running processes' or do anything that hooks into the
state of the browser, it needs to either be done once or done in in a
reloadable way.

The least complicated way to write reloadable code is to implement a
lifecycle pattern that takes down the previous system and rebuilds a
new one before injecting the current state.

This tearing down and rebuilding of the system is simply sane
lifecycle management and comes baked into Reactjs and Om.

Please check out the example project in the `example` directory. 

### Using your own server

You do not have to use the figwheel server to host your app and its
static assets. You can use your own server. 

To use your own server simply navigate to your server url for the page
that is hosting your ClojureScript app.

In this case, you have to let the figwheel client know where figwheel
websocket is.

Like so:

```
(fw/watch-and-reload
  :websocket-url   "ws://localhost:3449/figwheel-ws"
  :jsload-callback (fn [] (print "reloaded")))
```


## Writing reloadable code

Still working on writing this ...

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
