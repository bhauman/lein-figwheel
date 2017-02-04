# example application with a ring handler embeded in Figwheel

This application demonstrates the common way of using Figwheel in
web-development, where you embed a :ring-handler into Figwheel

## Overview

This application demonstrates a small Clojure application that uses Figwheel for
ClojureScript development

## Setup

To get an interactive development environment run:

    lein figwheel

This will compile everything and launch a browser at http://localhost:3449

The repl will launch and your can easily test it with 

    (js/alert "Am I connected?")

You now have a complete dev environment setup.

## Notes 

Please take some time to look at the `project.clj` and the `src/example/server_handler.clj`

## Not for production

Using the ring-handler is intended only for initial rapid developement
it doesn't give you a path to deployment. For that you will need to
have your own ring server apart from Figwheel.

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
