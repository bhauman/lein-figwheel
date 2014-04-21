# lein-figwheel

File Eval Gui Loop > FEGL > FEGUIL > "figwheel"

#### Still a work in progress. But that doesn't mean its not awesome.

A Leiningen plugin that builds your ClojureScript and pushes cljs
changes to the browser.

### What actually happens

This plugin starts the cljsbuild auto builder, opens a websocket and a
starts static file server. When you save a cljs file, cljsbuild will
detect that and compile it and other affected files. It will then pass
a list those changed files off to the figwheel server. The
figwheel server will in turn push the paths of the relevant
compiled javascript files through a websocket so that the browser can
reload them.

There is also a figwheel client that you include in your cljs
project to start a process which listens for changes and reloads the
files.

The main motivation for lein-figwheel is to allow for the
interactive development of ClojureScript. It doesn't provide this out
of the box, the developer has to take care to make their code reloadable.

## Usage

First include lein-figwheel the `:plugins` section of your
project.clj.

    [lein-figwheel "0.1.0-SNAPSHOT"]

You have to have your lein-cljsbuild configuration set up in your
project.clj.

Here is an example:

    :cljsbuild {
      { :builds [ { :id "example" 
                    :source-paths ["src/"]
                    :compiler { :output-to "resources/public/js/compiled/example.js"
                                :output-dir "resources/public/js/compiled/out"
                                :externs ["resources/public/js/externs/jquery-1.9.js"]
                                :optimizations :none
                                :source-map true } } ] } 
    }

The important part here is that you have to have at least one `build`
and that build has to have `:optimizations` set to `:none`.

The output directory has to be in a directory that is visible by the
static webserver. The default for the webserver root is
"resources/public" so your output files need to be in a subdirectory
of "resources/public" unless you change the webserver root.

Start the figwheel server. (This will get the first optimizations
none build)

    $ lein figwheel

or optionally give the name of the build

    $ lein figwheel example

This will start a server at `http://localhost:8080` with your
resources being served via the compojure `resources` ring handler.

## Client side usage

In your project.clj you need to include figwheel in your dependencies.

    [figwheel "0.1.0-SNAPSHOT"]

Make sure you have setup an html file to host your cljs. For example
you can create this `resources/public/livedev.html` file:

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

In keeping with the above previous examples you would put this into
your `src/example/core.cljs`:

    (ns example.core
      (:require
       [figwheel.client :as fw :include-macros true]))

    (enable-console-print!)

    (println "You can change this line an see the changes in the dev console")

    ;; the callback is optional
    (fw/defonce reloader
      (fw/watch-and-reload
       :websocket-url ""
       :jsload-callback (fn [] (print "reloaded")))

We are starting the reload watcher and we are wrapping it in a
`defonce`. As this file will be reloaded on change we have to make
sure that when we start 'running processes' or doing anything that
hooks into the state of the browser, we need to either do it once or
do it in a reloadable way where we teardown and the rebuild the
running system.

This tearing down and rebuilding of the system is simply sane
lifecycle management and comes baked into Reactjs and Om by proxy.

Please check out the example project in the `example` directory.

## Writing reloadable code



## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
