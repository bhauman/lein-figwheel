# node-example

A simple figwheel node example.

Make sure you have ws installed

    npm install ws
	
Start figwheel

    lein figwheel
   
Start node with devtools debugging

    node target/js/compiled/node_example.js

Make sure your REPL isn't connecting to a browser.

Now you can interact in the REPL and edit and save `src/node_example/core.cljs` 
to see your changes being hot loaded by Figwheel.

## Devtools remote debugging 

You can easily attach a remote Devtools debugger if you add
`--inspect` to your node command like so:

    node --inspect target/js/compiled/node_example.js

It will provide you with a url to visit with the Chrome browser

## Things of note in the project.clj

In the `project.clj` you will see the build has `:target :nodejs`.  It
is also important to set `:asset-path` so that you can launch node
from the root of the project.

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
