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

We'll use leinigen for dependencies and our `project.clj` should look
like this:

```clojure
(defproject example "0.1.0-SNAPSHOT"
  :description "Sidecar example"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.145"]]
  :profiles {
    :dev {
      :source-paths ["dev"]
      :dependencies [[figwheel-sidecar "0.5.0"]]
    }
  }  
)
```

Let's start the Clojure REPL as so:

```
rlwrap lein run -m clojure.main
```

## Loading the config

You can get the config from the `figwheel.edn`


```clojure
=> (require '[figwheel-sidecar.system :as sys])
nil
=> (require '[clojure.pprint :refer pprint])
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

The call to fetch config will attempt to get config first from
`figwheel.edn` and if there is no config available there it will look
for and read the `project.clj` file and attempt to get the config from
the `:figwheel` and `:cljsbuild` entries. When reading the
`project.clj` directly **no leiningen profile merging will occur**.

## The Figwheel Server component

```clojure
=> (require '[com.stuartsierra.component :as component])
nil
=> (def system (atom (component/system-map
                      :figwheel-server (sys/figwheel-server (sys/fetch-config)))))
```

This creates a system with a figwheel server in it. Now lets start the
server and start autobuilding our build.

```
=> (swap! system component/start)
Figwheel: Starting server at http://localhost:3449
#<SystemMap>
```

Now the Figwheel server is running.
Now we want to start autobuilding our ClojureScript.

```








