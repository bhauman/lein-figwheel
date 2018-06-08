# Questions/Explorations about the semantics of command line combinations

#### What command line args will cause figwheel to autobuild and insert code to establish a repl connection?

For the following examples assume `dev.cljs.edn` is in the current
directory and contains:

```clojure
{:main example.core}
```

Acknowledge that there is need to simply autobuild without a server or
server connection.

    -w src -c example.core

This should autobuild without a server or repl connection. If one
wants to supply the compile options resident in a figwheel build
config file (i.e. dev.cljs.edn) one can simply pass that config as a
normal `cljs.main` `-co` flag arg:

    -w src -co dev.cljs.edn -c

and the above will not insert any repl or figwheel functionality into
the build or build process.

The same is true for a single compile without watching of any kind.

    -co dev.cljs.edn -c
    
and

    -c example.core
    
should only compile once and have no figwheel libraries or
other functionality inserted into it.

But once you add a `--repl` or `-serve` flag to the operation and the
compile `:optimizations` level is `:none` then figwheel specific
functionality will come into play.

So for a command of:

    -co dev.cljs.edn -c -r

Figwheel main will take actions to try and create a figwheel
autobuilding development session.

1. a build name `dev` will be infered from the `dev.cljs.edn` file name
1. if no configured watch directories are found `figwheel.main` will
   try to infer one from the given namespace if it can find it
2. it will insert `[figwheel.repl.preload figwheel.core figwheel.main]` into
   the `:preloads` of the compile options
3. it will add a `figwheel.repl/connect-url` and perhaps some other
   configuration into `:closure-defines` of the compile options
4. it will start a server, repl, and launch a browser to connect to
   the repl server

When you only ask for a server via:

    -co dev.cljs.edn -c -s
    
A REPL will not be launched but all of the above steps will still be taken.

There is a shortcut *main* option flag `-b` or `--build` which can be
used in place of the `-c` flag.

The following examples are equivalient

    -b dev -r  ==  -co dev.cljs.edn -c -r
    
    -b dev -s  ==  -co dev.cljs.edn -c -s
    
    -b dev     ==  -co dev.cljs.edn -c -s
    
So when you use the `--build` flag you will normally get a server as well.

#### How to build once with the `--build-once` or `-bo` flag

#### Background builds and the `--bb` flag

#### Turning various features off

#### How do I better determine the behavior of a set of command line args?

There is a `--pprint-config` or `-pc` *init* arg which when added to
the command line like so:

    -pc -co dev.cljs.edn -c example.core -s
    
Will print out useful information about the resulting configuration.




