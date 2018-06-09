# Welcome to ClojureScript!

This page hosts the evaluation environment for the Read Eval Print
Loop (REPL) that you just launched. You can test the connection by
returning to the REPL and typing 

```javascript
cljs.user=> (js/alert "Hi!")
```

# New to ClojureScript?

If you are new to the magical world of ClojureScript you may want to
spend some time at the REPL prompt `cljs.user=>`. This guide to
[Javascript/ClojureScript synonyms](https://kanaka.github.io/clojurescript/web/synonym.html){:target="_blank"}
is pretty helpful. Exploring this
[cheetsheet](http://cljs.info/cheatsheet/){:target="_blank"} will also provide some
bearings for you while you explore this new terrain.

# What is figwheel.main?

`figwheel.main` is a tool that is very similar in function to
[`cljs.main`](https://clojurescript.org/guides/quick-start){:target="_blank"}, if you
are unfamiliar with `cljs.main` you should really take some time to to
read the
[ClojureScript Quick Start](https://clojurescript.org/guides/quick-start){:target="_blank"}. It
will be time well spent.

The main difference between `figwheel.main` and `cljs.main` in that it
will hot reload your code as you work on it.
