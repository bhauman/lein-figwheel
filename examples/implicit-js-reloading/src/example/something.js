goog.provide("example.something");

/* This file will be compiled by the ClojureScript compiler without
 * needing a foreign libs declaration in the build configuration. It's
 * position in the filesystem follows mirrors the its "namespace"
 * following the same conventions as ClojureScript.
 * Figwheel will hot reload this file as you save it.
*/

example.something.hello = function() {return "Hey there from example.something JavaScript";}

console.log("This is printed at load time from example.something JavaScript");
