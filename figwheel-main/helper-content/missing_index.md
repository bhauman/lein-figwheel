# Welcome to Figwheel&apos;s default development page

> **TLDR**: you can use your own `index.html` page instead of this helper page
> by adding creating a `resources/public/index.html` file as detailed
> [here](#providing-your-own-indexhtml)

This page is currently hosting your application code and REPL (Read
Eval Print Loop). As you change your code and save it, the
changed code will be hot loaded into this browser page. 

If you return to the terminal where you launched `figwheel` and enter
valid ClojureScript code at the `cljs.user=>` prompt, the code will be
compiled to Javascript and evaluated in the JavaScript environment on
this page.

If you close this page the REPL will cease to function.

# Index.html not provided

You are here because you have not provided your own `index.html` page
to host your application yet.

There is nothing wrong with this. You can use this page as a scaffold
to develop your project on for as long as its helpful.

If your code needs to work with HTML elements on this page there is a
convenient `<div id="app">` provided for this purpose. When you
replace the contents of the `app` element it will remove all the
current content and style from this page.

> An file named `index.html` does not have to be provided. You can
> provide any other html file as a host page. For example
> `resources/public/app.html`. In this case, you may want to change
> `:open-url` to
> `"http://[[server-hostname]]/[[server-port]]/app.html"` in order to
> change the launch page.

# Providing your own index.html

You can create your own `index.html` and place it in
`resources/public/index.html` and it will be displayed instead of this
page.

The most important part of creating your `index.html` page is ensuring
that your compiled ClojureScript gets loaded onto the page.

To get started place the following HTML code at `resources/public/index.html`:






