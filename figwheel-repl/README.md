# figwheel-repl

Figwheel-REPL is intended to provide a best of class repl-env for
ClojureScript.

> Currently a work in progress.

Figwheel-REPL is only a ClojureScript `repl-env` and doesn't do anything
specific to aid help with automatic file reloading. As such it is more
similar to Weasel in function than to Figwheel.

It is intended to be a single `repl-env` that will work on as many
platforms as possible Browser, Node, Worker, ReactNative, etc.

It is also intended to handle multiple clients, think browser tabs,
much more gracefully than the current Figwheel REPL.

It is also different in that it only evaluates code on a single client
by default. You will still be able to choose to broadcast an eval op
to all connected clients if you prefer. You can also provide a filter
function when you create the Figwheel repl-env, to filter the
connections to the set of connected clients you want an eval op to be
sent to.

## Multiple REPL behavior

The new `figwheel.repl` namespace currently offers some ClojureScript
functions to help you list and choose which connected client to focus on.

The `figwheel.repl/conns` macro allows you to list the connected clients:

For example:

```
cljs.user> (figwheel.repl/conns)
Will Eval On:  Darin
Session Name     Age URL
Darin            25m /figwheel-connect
Judson          152m /figwheel-connect
nil
```


The above `figwheel.repl/conns` call lists the clients available for the
REPL to target.

All connections are given easy to remember session names. The
intention is that this will help you easily identify which browser tab
your, through the REPL client feedback in the browsers dev-tool
console.

The `Will Eval On: Darin` indicates that the `Darin` client is where
the next eval op will be sent to because this is currently the
youngest connected client.

This default client heuristic allows for a simple understanding of
which REPL is the current target of eval operations. Open a new
browser tab, or start an new node instance and that latest one will
now be the current eval target.

If you want to focus on a specific client,

```
cljs.user> (figwheel.repl/focus Judson)
Focused On: Judson
```

From now on all evals will go to `Judson` unless the connection is
lost in which case the behavior will return to selecting the youngest
connection.

You can confirm that the repl is currently focused with:

```
cljs.user> (figwheel.repl/conns)
Focused On: Judson
Session Name     Age URL
Darin            28m /figwheel-connect
Judson          155m /figwheel-connect
nil
```

I think this goes a long way toward solving a problem that has existed
since the very beginning of Figwheel.

## Attention toward embedding the figwheel-repl endpoint

The other problem that I'm currently trying to work out is how to best
support embedding the Figwheel REPL endpoint in your server.

For larger projects it simplest to use figwheel connection as a
side-channel, a separate REPL connection, that is distinct from your
projects HTTP server. Figwheel's use of Web-sockets and CORS make this
side connection a simple matter. But inevitably there are situations
where you want to embed the Figwheel endpoint in your server. So I'm
giving this some serious attention.

In addition to the Web-socket connection, I have implemented a simple
HTTP polling connection which should allow anyone to embed
figwheel-repl ring middleware into their stack. (Side note: I'm also
looking at long polling).

It is too bad that as a community we haven't landed on an agreed upon
Ring web-socket interface, as this makes it much harder to allow simple
embedding of a web-socket endpoint into the server of your choice. But
I'm going to do my best to facilitate this by making it easier to
create a web-socket endpoint from the provided api.

On a side note: I'm also considering making the default server a the
`ring.jetty.adapter` as it is such a common dependency.

## License

Copyright © 2018 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
