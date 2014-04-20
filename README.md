# lein-cljs-livereload

A Leiningen plugin that builds your ClojureScript via (lein-cljsbuild)
and then pushes the names of the changed compiled javascript files to
the browser via a websocket.

The main motivation for lein-cljs-livereload is to allow for the
interactive development of ClojureScript. It doesn't provide this out
of the box, the developer has to take care to make their code reloadable.

clsj-livereload includes a static webserver for development convenience.

## Usage

First you have to already have your lein-cljsbuild configuration set
up in your project.clj.

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

The important part here is that you have to have at least one
`build`. That build has to have `:optimizations` set to `:none`.

The output directory has to be in a directory that is visible by the
static webserver. The default for the webserver root is
"resources/public" so your output files need to be in a subdirectory
of "resources/public" unless you change the webserver root.

Start the auto compiling server 

    $ lein cljs-livereload

This will start a server at `http://localhost:8080` with your
resources being served via the compojure `resources` ring handler.

## Client side usage

WIP


## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
