# Fighweel Sidecar

Let's start with a sketch of how we use the components in Figwheel Sidecar.

First let's create a `figwheel.edn` configuration file in the root of
our project.

```clojure
{
  :http-server-root "public" ;; default
  :server-port 3449          ;; default
  :open-file-command "emacsclient"
  :builds [{:id "example", 
            :source-paths ["src"],
            :figwheel true
            :build-options
            {:main example.core,
             :asset-path "js/out",
             :output-to "resources/public/js/example.js",
             :output-dir "resources/public/js/out",
             :source-map-timestamp true}}]
}
```

We'll use leinigen for dependency and classpath management and our
`project.clj` should look like this:

```clojure
(defproject example "0.1.0-SNAPSHOT"
  :description "Sidecar example"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.145"]]
  :profiles {
    :dev {
      :dependencies [[figwheel-sidecar "0.5.0"]]
    }
  }  
)
```

Start the Clojure REPL:

```
rlwrap lein run -m clojure.main
```

## Loading the config

You can get the config from the `figwheel.edn`


```clojure
=> (require '[figwheel-sidecar.system :as sys])
nil
=> (require '[clojure.pprint :refer [pprint]])
nil
=> (pprint (sys/fetch-config))
{:figwheel-options
 {:http-server-root "public",
  :server-port 3449,
  :open-file-command "emacsclient"},
 :all-builds
 [{:id "example",
   :source-paths ["src"],
   :figwheel {:build-id "example"},
   :build-options
   {:main example.core,
    :asset-path "js/out",
    :output-to "resources/public/js/example.js",
    :output-dir "resources/public/js/out",
    :source-map-timestamp true,
    :optimizations :none}}],
 :build-ids ["example"]}
```

`fetch-config` fetches the config from the `figwheel.edn` file and
prepares it for consumption by figwheel components.

The call to `fetch-config will attempt to get config first from
`figwheel.edn` and if there is no `figwheel.edn` available, it will
look for and read the `project.clj` file and attempt to get the
configuration info from the `:figwheel` and `:cljsbuild` entries. When
reading the `project.clj` directly **no leiningen profile merging will
occur**.

## The Figwheel Server component

```clojure
=> (require '[com.stuartsierra.component :as component])
nil
=> (def system (atom (component/system-map
                      :figwheel-server (sys/figwheel-server (sys/fetch-config)))))
```

This creates a system with a `:figwheel-server` in it.

Now lets start the server and start autobuilding our build.

```
=> (swap! system component/start)
Figwheel: Starting server at http://localhost:3449
#<SystemMap>
```

Now that the figwheel server is running we can start autobuilds.

## Starting an autobuild

The varios system control functions that are available in the CLJS
repl are in the `figwheel-sidecar.system` namespace. 

We can use the `figwheel-sidecar.system/start-autobuild` function to
start autobuilding our ClojureScript.

```
=> (swap! system sys/start-autobuild ["example"])
Figwheel: Starting autobuild
Figwheel: Watching build - example
Compiling "resources/public/js/tryfig.js" from ["src"]...
Successfully compiled "resources/public/js/tryfig.js" in 12.445 seconds.
#<SystemMap>
```

Now if we insect the keys of the system map we will see that there
is a new key.

```
=> (keys @system)
(:figwheel-server "autobuild-example")
```

The `"autobuild-example"` component has been added to the system. So
now wecan start and stop the system and the autobuild will start and
stop as well.

Let's stop the system and build another one.

```
(swap! system component/stop)
Figwheel: Stopped watching build - example
Figwheel: Stopping Websocket Server
Mon Oct 26 10:41:31 EDT 2015 [main] ERROR - increase :queue-size if this happens often
java.util.concurrent.RejectedExecutionException:
....
```

**This exception is expected** 

This time let's add our autobuilder by hand. This is not necessary but
it's helpful to understand what is going on.

```clojure
(def system
   (atom
           ;; figwheel-server sets up and contains state for all
           ;; figwheel interactions so we will get our prepared build-config
           ;; from our instantiated figwheel-server component
     (let [fig-server (sys/figwheel-server (sys/fetch-config))
           build-config (get (:builds fig-server) "example")]
        (component/system-map
            :figwheel-server fig-server
            "autobuild-example"
            (component/using
               (sys/cljs-autobuild {:build-config build-config})
               [:figwheel-server])))))
```

The format of the "autobuild-example" key is used by the various
system control functions that start and stop cljs-autobuilds and
facilitates these special control functions in the repl.

Now you can start this system.

```
=> (swap! system component/start)
Figwheel: Starting server at http://localhost:3449
Figwheel: Watching build - example
Compiling "resources/public/js/tryfig.js" from ["src"]...
Successfully compiled "resources/public/js/tryfig.js" in 13.19 seconds.
#<SystemMap>
```

Voila! We have our autobuild running allong with the figwheel server.

You can edit your source files and everything will work in a very
Figwheel way. This doesn't include CSS watching yet so let's add that
next.

Let's stop the system:

```
=> (swap! system component/stop)
```

and now let's define a system with a CSS Watcher:

```clojure
(def system
   (atom
           ;; figwheel-server sets up and contains state for all
           ;; figwheel interactions so we will get our prepared build-config
           ;; from our instantiated figwheel-server component
     (let [fig-server (sys/figwheel-server (sys/fetch-config))
           build-config (get (:builds fig-server) "example")]
        (component/system-map
            :figwheel-server fig-server
            "autobuild-example"
            (component/using
               (sys/cljs-autobuild {:build-config build-config})
               [:figwheel-server])
            :css-watcher   
            (component/using
               (sys/css-watcher {:watch-paths ["resources/public/css"]})
               [:figwheel-server])))))               
```

And we can start this as well:

```
=> (swap! system component/start)
Figwheel: Starting server at http://localhost:3449
Figwheel: Watching build - example
Compiling "resources/public/js/tryfig.js" from ["src"]...
Successfully compiled "resources/public/js/tryfig.js" in 0.658 seconds.
Figwheel: Starting CSS Watcher for paths  ["resources/public/css"]
#<SystemMap>
```

The main idea here is that the figwheel server is the single dependent
for other components that want to send messages to the client.

## Starting the REPL

Now that we have a system with a :figwheel-server key and some builds
we can start a figwheel REPL.

```
=> (sys/figwheel-cljs-repl system)
Launching ClojureScript REPL for build: example
Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (reload-config)                 ;; reloads build config and resets autobuild
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (print-config [id ...])         ;; prints out build configurations
          (fig-status)                    ;; displays current state of system
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object
Prompt will show when Figwheel connects to your application
To quit, type: :cljs/quit
cljs.user=> (+ 1 2)
3
cljs.user=> :cljs/quit
true
```

Or you can start a build switching repl which let's you switch to
another build when you quit the REPL.

```
=> (sys/build-switching-cljs-repl system)
Launching ClojureScript REPL for build: example
Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (reload-config)                 ;; reloads build config and resets autobuild
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (print-config [id ...])         ;; prints out build configurations
          (fig-status)                    ;; displays current state of system
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object
Prompt will show when Figwheel connects to your application
To quit, type: :cljs/quit
cljs.user=> :cljs/quit
Choose focus build for CLJS REPL (example) or quit > quit
```


