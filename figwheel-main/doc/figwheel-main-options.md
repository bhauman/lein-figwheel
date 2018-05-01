# Figwheel Main Configuration Options

The following options can be supplied to `figwheel.main` via the `figwheel-main.edn` file.

# Commonly used options (in order of importance)

## :watch-dirs

A list of ClojureScript source directories to be watched and compiled on change.

    :watch-dirs ["cljs-src"]

## :css-dirs

A list of CSS source directories to be watched and reloaded into the browser.

    :css-dirs ["resource/public/css"]

## :ring-handler

A symbol or string indicating a ring-handler to embed in the
figwheel.repl server. This aids in quickly getting a dev server up and
running. If the figwheel server doesn't meet your needs you can simply
start your own server, the figwheel.client will still be able to
connect to its websocket endpoint.
Default: none

    :ring-handler my-project.server/handler

## :ring-server-options

All the options to forward to the `ring-jetty-adapter/run-jetty` function
which figwheel.main uses to run its ring server.

All the available options are documented here:
https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/src/ring/adapter/jetty.clj#L127

This will normally be used to set the `:port` and `:host` of the server.

Most uses of these options are considered advanced if you find
yourself using many of these options you problably need to run your
own server outside of figwheel.main.

## :rebel-readline

By default Figwheel engauges a Rebel readline editor when it starts
the ClojureScript Repl in the terminal that it is launched in.

This will only work if you have `com.bhauman/rebel-readline-cljs` in
your dependencies.

More about Rebel readline:
https://github.com/bhauman/rebel-readline

Default: true

    :rebel-readline false

## :pprint-config

When `:pprint-config` is set to true. The `figwheel.main` will print the
computed config information and will terminate the process. Useful for
understanding what figwheel.main adds to your configuration before it
compiles your build.

Default: false

    :pprint-config true

## :open-file-command

A path to an executable shell script that will be passed a file and
line information for a particular compilation error or warning.

A script like this would work
ie. in  ~/bin/myfile-opener

    #! /bin/sh
    emacsclient -n +$2:$3 $1

The add this script in your config:

    :open-file-command "myfile-opener"

But thats not the best example because Figwheel handles `emacsclient`
as a special case so as long as `emacsclient` is on the shell path you can
simply do:

    :open-file-command "emacsclient"

and Figwheel will call emacsclient with the correct args.

## :figwheel-core

Wether to include the figwheel.core library in the build. This
 enables hot reloading and client notification of compile time errors.
 Default: true

    :figwheel-core false

## :hot-reload-cljs

Whether or not figwheel.core should hot reload compiled
ClojureScript. Only has meaning when :figwheel is true.
Default: true

    :hot-reload-cljs false

## :connect-url

The url that the figwheel repl client will use to connect back to
the server.

This url is actually a template that will be filled in.  For example
the default `:connect-url` is:

    "ws://[[config-hostname]]:[[server-port]]/figwheel-connect"

The available template variables are:

For the server side:

    [[config-hostname]]  the host supplied in :ring-server-options > :host or "localhost"
    [[server-hostname]]  the java.InetAddress localhost name - "Bruces-MacBook-Pro.local" on my machine
    [[server-ip]]        the java.InetAddress localhost ip interface - normally 192.168.x.x
    [[server-port]]      the host supplied in :ring-server-options > :host or "localhost" or the default port

On the client side:
    [[client-hostname]]  the js/location.hostname on the client
    [[client-port]]      the js/location.port on the client

If the url starts with a Websocket scheme "ws://" a websocket
connection will be established. If the url starts with an http scheme
"http" an http long polling connection will be established.

## :reload-clj-files

Figwheel naively reloads `clj` and `cljc` files on the `:source-paths`.
It doesn't reload clj dependent files like tools.namspace.

Figwheel does note if there is a macro in the changed `clj` or `cljc` file
and then marks any cljs namespaces that depend on the `clj` file for
recompilation and then notifies the figwheel client that these
namespaces have changed.

If you want to disable this behavior:

    :reload-clj-files false

Or you can specify which suffixes will cause the reloading

    :reload-clj-files #{:clj :cljc}

## :log-file

The name of a file to redirect the figwheel.main logging to. This
will only take effect when a REPL has been started.

    :log-file "figwheel-main.log"

## :log-level

The level to set figwheel.main java.util.logger to.
Can be one of: `:error` `:info` `:debug` `:trace` `:all` `:off`

    :log-level :error

## :log-syntax-error-style

figwheel.main logging prints out compile time syntax errors which
includes displaying the erroneous code.
Setting `:log-syntax-error-style` to `:concise` will cause the logging to
not display the erroneous code.
Available options: `:verbose`, `:concise`
Default: `:verbose`

    :log-syntax-error-style :concise

## :load-warninged-code

If there are warnings in your code emitted from the compiler, figwheel
does not refresh. If you would like Figwheel to load code even if
there are warnings generated set this to true.
Default: false

    :load-warninged-code true

## :ansi-color-output

Figwheel makes an effort to provide colorful text output. If you need
to prevent ANSI color codes in figwheel output set `:ansi-color-output`
to false.  Default: true

    :ansi-color-output false

## :validate-config

Whether to validate the figwheel-main.edn and build config (i.e.".cljs.edn") files.
Default: true

    :validate-config false

## :target-dir

A String that specifies the target directory component of the path
where figwheel.main outputs compiled ClojureScript

The default `:output-dir` is composed of:

    [[:target-dir]]/public/cljs-out/[[build-id]]

The default `:output-to` is composed of:

    [[:target-dir]]/public/cljs-out/[[build-id]]-main.js

If you are using the default figwheel.repl server to serve compiled
assets, it is very important that the :target-dir be on the classpath.

The default value of `:target-dir` is "target"

    :target-dir "cljs-target"

# Rarely used options

## :client-print-to

The `figwheel.repl` client can direct printed (via pr) output to the
repl and or the console. `:client-print-to` is a list of where you
want print output directed. The output choices are `:console` and `:repl`
Default: [:console :repl]

    :client-print-to [:console]

## :ring-stack

The fighweel server has a notion of a `:ring-stack`. The
`:ring-stack` is a composition of basic ring-middleware (think
sessions) to wrap around a supplied `:ring-handler`.

The default `:ring-stack` is a slightly modified
`ring.middleware.defaults/wrap-defaults`

## :ring-stack-options

The fighweel.repl server has a notion of a `:ring-stack`. The
`:ring-stack` is a composition of basic ring-middleware to wrap around
a supplied `:ring-handler`.

The default `:ring-stack` is a slightly modified
ring.middleware.defaults/wrap-defaults.

`:ring-stack-options` are the options that figwheel.repl supplies to
`ring.middleware.defaults/wrap-defaults`.

The default options are slightly modified from `ring.middleware.defaults/site-defaults`:

```
{:params
 {:urlencoded true, :multipart true, :nested true, :keywordize true},
 :cookies true,
 :session
 {:flash true, :cookie-attrs {:http-only true, :same-site :strict}},
 :static {:resources "public"},
 :responses {:content-types true, :default-charset "utf-8"},
 :figwheel.server.ring/dev
 {:figwheel.server.ring/fix-index-mime-type true,
  :figwheel.server.ring/resource-root-index true,
  :figwheel.server.ring/wrap-no-cache true,
  :ring.middleware.not-modified/wrap-not-modified true,
  :co.deps.ring-etag-middleware/wrap-file-etag true,
  :ring.middleware.cors/wrap-cors true,
  :ring.middleware.stacktrace/wrap-stacktrace true}}
```

You can override these options by suppling your own to `:ring-stack-options`

If these options are changed significantly don't be suprised if the
figwheel stops behaving correctly :)

## :wait-time-ms

The number of milliseconds to wait before issuing reloads. Set this
higher to wait longer for changes. This is the interval from when the first
file change occurs until we finally issue a reload event.

Default: 50

    :wait-time-ms 50

## :mode

The `:mode` indicates the behavior that occurs after a compile.
Options: `:repl` `:serve` or `:build-once`

* `:repl` indicates that repl sill be started
* `:serve` indicates that a server will be started
* `:build-once` indicates that a compile will not be follwed by any action

This is mainly intended for use when you are launching figwheel.main from a script.

Normally defaults to `:repl`