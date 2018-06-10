# Setting up a Figwheel build with leiningen

If you are ready to start working on a project with Figwheel Main and
[Leiningen](https://leiningen.org/) then it will be most helpful to
set up a **build**.

> A **build** is the configuration of a compile task, that determines
> what files get compiled with a certain set of compile options. A
> build also optionally configures the which features Figwheel
> enguages while you are working on your application.

#### project.clj

First off, it is assumed that if you made it this far you already have
a `project.clj` file in the directory that you launched this REPL from.

If don't have a `project.clj` file let's create one now:

```clojure
(defproject lein-main "0.1.0-SNAPSHOT"
  :profiles {:dev {:dependencies
                   [[com.bhauman/figwheel-main "0.1.1-SNAPSHOT"]
                    [com.bhauman/rebel-readline-cljs "0.1.3"]]}}
  :aliases {"fig" ["trampoline" "run" "-m" "figwheel.main" "--"]})
```

Above we created an alias to help us launch figwheel as a leiningen task.

#### dev.cljs.edn

Next you will need to create a minimal **build** configuration. We will
create a configuration file for a build named `dev`.

In `dev.cljs.edn`:

```clojure
{:main hello.core}
```

At the very least you will need to define the entry point to your
build, i.e. the top level namespace for your build. 

There are many other
[compile options](https://clojurescript.org/reference/compiler-options)
that you can configure in this file. For most cases however all you
will need is the above.

### src/hello/core.cljs

Next let's create an initial ClojureScript file for our project.

In `src/hello/core.cljs` put:

```clojure
(ns hello.core)

(enable-console-print!)

(defn hello [] "hello There")

;; uncomment this to alter the provided "app" DOM element
;; (set! (.-innerHTML (js/document.getElementById "app") (hello)))

(println (hello))
```

### resources/public/index.html (optional)

The `resources/public/index.html` file is optional because Figwheel
provides a default one (much like the page you are looking at now) and
you can get pretty far overriding the html element `<div id="app">`.

If you want to provide your own `index.html` here's one you can start
from the following example.

In `resources/public/index.html` put:

```html
<!DOCTYPE html>
<html>
  <head>
  </head>
  <body>
    <div id="app"></div>
    <script src="cljs-out/dev-main.js"></script>
  </body>
</html>
```

## Building the build

Once you have your files in place, the file tree should at least look like this:

```text
hello-world
├─ dev.cljs.edn
├─ deps.edn
└─ src
   └─ hello_world <- underscore is very important here
      └─ core.cljs
```

Now you can start an auto-building / hot-reloading process.

In the directory that is the root of the project (the `hello-world` directory),
execute the following shell command.

```shell
lein fig -- -b dev -r
```

> Keep in mind that when you invoke the `fig` alias, you will always
> want to include `--` after the `fig` and before any figwheel.main
> args.

This will launch and autobuild process that compiles your code as you
save it. A browser window will pop open and the terminal that you
launched the command from will now be running a ClojureScript REPL that
is attached to the browser.

From here you will be able to edit the ClojureScript file and have it
hot loaded into the browser on save. 
