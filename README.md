# lein-figwheel

File Eval Gui Loop > FEGL > FEGUIL > "figwheel"

![Fig wheel on a drink](http://s3.amazonaws.com/bhauman-blog-images/Fig-Sidecar_Pomegranate-Bistro1-1.jpg)

#### Still a work in progress. But that doesn't mean it's not a cool garnish.

A Leiningen plugin that builds your ClojureScript and pushes the
changes to the browser.

### What actually happens

This plugin starts the cljsbuild auto builder, opens a websocket and
starts static file server. When you save a cljs file, cljsbuild will
detect that and compile it and other affected files. It will then pass
a list those changed files off to the figwheel server. The
figwheel server will in turn push the paths of the **relevant**
compiled javascript files through a websocket so that the browser can
reload them.

There is also a figwheel client that you need to include into your cljs
project to start a process which listens for changes and reloads the
files.

The main motivation for lein-figwheel is to allow for the interactive
development of ClojureScript. Figwheel doesn't provide this out of the
box, the developer has to take care to make their code reloadable. 

If you are using React or Om it's not hard to write reloadable code,
in fact you might already be doing it.

See the introductory blog post [here](http://rigsomelight.com/2014/05/01/interactive-programming-flappy-bird-clojurescript.html).

## Demo

Here is a [live demo of using figwheel](https://www.youtube.com/watch?v=KZjFVdU8VLI)

## Quick Start

You can get started quickly with the flappy bird demo:

    git clone https://github.com/bhauman/flappy-bird-demo.git

then cd into `flappy-bird-demo` and type

    lein figwheel

If you would prefer to greenfield a new project you can use the figwheel leinigen template.

    lein new figwheel hello-world

## Usage

First include lein-figwheel the `:plugins` section of your
project.clj.

```clojure
[lein-figwheel "0.1.0-SNAPSHOT"]
```

You have to have your lein-cljsbuild configuration set up in your
project.clj.

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

The output directory has to be in a directory that can be served by the
static webserver. The default for the webserver root is
"resources/public" so your output files need to be in a subdirectory
of "resources/public" unless you change the webserver root.

Start the figwheel server. (This will get the first optimizations
none build)

    $ lein figwheel

or optionally give the name of the build

    $ lein figwheel example

This will start a server at `http://localhost:3449` with your
resources being served via the compojure `resources` ring handler.

So you can load the a html file thats hosting your ClojureScript app
by going to `http://localhost:3449/<yourfilename>.html`

## Client side usage

In your project.clj you need to include figwheel in your dependencies.

```clojure
[figwheel "0.1.0-SNAPSHOT"]
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
  ;; :websocket-url "ws:localhost:3449/figwheel-ws" default
  :jsload-callback (fn [] (print "reloaded"))) ;; optional callback
```

The call to `watch-and-reload` is idempotent and can be called many
times safely. As this file will be reloaded on change we have to make
sure that when we start 'running processes' or do anything that hooks
into the state of the browser, it needs to either be done once or done in
in a reloadable way.

The best way to write reloadable code is to have lifecycle management
that takes down the previous system and rebuilds a new one before
injecting the current state.

This tearing down and rebuilding of the system is simply sane
lifecycle management and comes baked into Reactjs and Om.

Please check out the example project in the `example` directory. 

## Writing reloadable code

Still working on writing this ...

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
