# node-example

A simple figwheel node example.

Make sure you have ws installed

    npm install ws
	
Start figwheel

    lein figwheel
   
Start node 

    node target/js/compiled/node_example.js

Make sure your REPL isn't connecting to a browser.

Now you can interact in the REPL and edit and save `src/node_example/core.cljs`

## Things of note in the project.clj

In the `project.clj` you will see the build has `:target :nodejs`.  It
is also important to set `:asset-path` so that you can launch node
from the root of the project.

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
