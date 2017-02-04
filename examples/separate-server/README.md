# example application with a server running outside of Figwheel

This application demonstrates the common way of using Figwheel in
web-development, where Figwheel runs along side your web server.

## Overview

This application demonstrates a small Clojure application that uses Figwheel for
ClojureScript development

## Setup

To get an interactive development environment run:

    lein figwheel

This will compile everything and launch a browser at http://localhost:3000

The repl will launch and your can easily test it with 

    (js/alert "Am I connected?")

You now have a complete dev environment setup.

## Ring server

A ring server is serving the application

If you ever want to just serve the application without running
figwheel you can just do:

    lein ring
	

## Deployment

You can easily create a standalone jar file which you can deploy to
Heroku or some other deployment container like so

    lein package
	
Please refer to the `project.clj` under `:aliases` to see how it works.

## Notes 

Please take some time to look at the `project.clj` and the `user.clj`

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
