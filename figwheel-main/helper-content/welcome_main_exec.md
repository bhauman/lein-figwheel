# The Main Function

You are here because you executed a `-main` function by supplying
`figwheel.main` with a `--main` or `-m` arg.

> a main function is a good way to kick of a task that you need to run
> in ClojureScript (i.e. run some tests)

If there were problems, you may want to open the DevTools console and
see if any errors occurred.

The function should be named `-main` and that the initial `-` is
required.

For example:

```clojure
(ns example.core)

(defn -main []
  (println "Hello"))
```

You can invoke the above function via:

```shell
clojure -m figwheel.main -m example.core
```

Keep in mind that any extra command line args supplied after
specifying the namespace will be available via `*command-line-args*`.

For example given the following ClojureScript:

```clojure
(ns example.main)

(defn -main []
  (prn *command-line-args*))
```

If the above is invoked with:

```shell
clojure -m figwheel.main -m example.core 1 2 3
```

It should print out `(1 2 3)`
