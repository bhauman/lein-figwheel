# Figwheel Sidecar

__The following currently assumes familiarity with Figwheel and its function__

Let's start with a sketch of how we can use the components in Figwheel Sidecar.

First let's use a new means configuration and create a `figwheel.edn`
file in the root directory of our project.

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
      :dependencies [[figwheel-sidecar "0.5.0-SNAPSHOT"]]
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

The call to `fetch-config` will attempt to get config first from
`figwheel.edn` and if there is no `figwheel.edn` available, it will
look for and read the `project.clj` file and attempt to get the
configuration info from the `:figwheel` and `:cljsbuild` entries. When
reading the `project.clj` directly **no leiningen profile merging will
occur**.

If you wish to load your configuration from a merged `project.clj` you
can load `leiningen.core` and read the configuration.

One can store and load the configuration however one wants to.
`fetch-config` is merely a convenience.

## The Figwheel System component

Let's start with the simplest system that we can make:

```clojure
=> (require '[com.stuartsierra.component :as component])
nil
=> (def system
     (component/system-map
       :figwheel-system (sys/figwheel-system (sys/fetch-config))))
```

This creates a system with a `:figwheel-system` in it.

Now let's start our system:

```
=> (alter-var-root #'system component/start)
Figwheel: Starting server at http://localhost:3449
Figwheel: Watching build - example
Compiling "resources/public/js/tryfig.js" from ["src"]...
Successfully compiled "resources/public/js/tryfig.js" in 3.247 seconds.
#<SystemMap>
```

To complete this simple example let's launch a Figwheel REPL:

```
=> (sys/cljs-repl (:figwheel-system system))
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
```

Remember that you won't see the REPL prompt until the repl connects to
your running application.

Sweet! Now you have all the functionality of Figwheel within the
context of a system map. This gives you the freedom to add arbitrary
components according to your need.

Let's stop this system in preparation for building another one.

```
(alter-var-root #'system component/stop)
Figwheel: Stopped watching build - example
Figwheel: Stopping Websocket Server
Mon Oct 26 10:41:31 EDT 2015 [main] ERROR - increase :queue-size if this happens often
java.util.concurrent.RejectedExecutionException:
....
```

**This exception is expected** 

## Adding the CSS Watcher component

The figwheel-system doesn't include css watching but we can add the
CSS watching as a seperate component.

and now let's define a system with a CSS Watcher:

```clojure
(def system
  (component/system-map
    :figwheel-system (sys/figwheel-system (sys/fetch-config))
    :css-watcher (sys/css-watcher {:watch-paths ["resources/public/css"]})))
```

The call to `sys/css-watcher` will create a file watcher that observes
changes in the `:watch-paths` and then fires off notifications to
listening figwheel clients.

And we can start this as well:

```
=> (alter-var-root #'system component/start)
Figwheel: Starting server at http://localhost:3449
Figwheel: Watching build - example
Compiling "resources/public/js/tryfig.js" from ["src"]...
Successfully compiled "resources/public/js/tryfig.js" in 0.658 seconds.
Figwheel: Starting CSS Watcher for paths  ["resources/public/css"]
#<SystemMap>
```

The main idea here is that the `:figwheel-server` is the single dependent
for other components that want to send messages to the client.

## Starting the REPL

Now that we have a system with some builds running we can start a Figwheel REPL.

```
=> (sys/cljs-repl (:figwheel-system system))
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
```

There is only one build in our configuration so the build switching
repl doesn't offer us much.

## Creating a component that communicates with the Figwheel client

Let's make a simple component that preiodically sends the current
server side time to the client. This has no practical value but will
just server as an example.

```clojure
(ns example.push-time-service
  (:require
    [com.stuartsierra.component :as component]
    [figwheel-sidecar.components.figwheel-server :as server]
    [clojure.core.async :refer [go-loop timeout]]))

(defrecord PushTimeService [figwheel-system]
  component/Lifecycle
  (start [this]
    (if-not (:time-service-run this)
      (let [run-atom (atom true)]
        (go-loop []
          (when run-atom
            (server/send-message figwheel-system
                                 ::server/broadcast
                                 {:msg-name :time-push :time (java.util.Date.)})
             (timeout 1000)
             (recur)))
       (assoc this :time-service-run run-atom))
     this))
   (stop [this]
     (if (:time-service-run this)
        (do (reset! (:time-service-run this) false)
            (dissoc this :time-service-run))
        this)))
```

This creates a service that sends a periodic message to all connected figwheel clients.

Let's add this to our system map.

```
(def system
  (component/system-map
    :figwheel-system (sys/figwheel-system (sys/fetch-config))
    :css-watcher (sys/css-watcher {:watch-paths ["resources/public/css"]})
    :time-pusher
    (component/using
      (PushTimeService.)
      [:figwheel-system])))
```

In the example above I am broadcasting the message if you want to
target a certain build just replace the `::server/broadcast` above
with the build id ("example" is build id from the config above).

Now let's listen for this message on the client. You will need to add
the following to your ClojureScript project source. You will probably
want to create a developement build that includes the source directory
that contains a source file as follows.

```clojure
(ns push-time.core
  (:require [figwheel.client :as fig]))
  
(fig/add-message-watch
  :time-pusher
  (fn [{:keys [msg-name] :as msg}]
    (when (= msg-name :time-push)
      (println "Recieved time message:" (prn-str (:time msg))))))
```

This will add a listener and whenever you receive a `:time-push`
message it will be printed in the console of the client.

Communicating with the figwheel client via Figwheel server should only
be used for development tooling. Figwheel is not intended to provide
support for application communication.


## Hooking into the autobuild with middleware
