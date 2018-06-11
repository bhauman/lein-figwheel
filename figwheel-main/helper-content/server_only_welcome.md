# Server Only mode

You are here because you launched `figwheel.main` in server only mode
**and** you have not provided a default `index.html` file.

**Server only mode** is entered when you only specify `--server` or
`-s` as the only **main option** to `figwheel.main`.

**Server only mode** only serve files and ring endpoints defined
`:ring-handler`. This mode does not compile your source code or create
a REPL connection to this web page. If you are wanting to compile
ClojureScript source files as well as create a REPL connection to the
Browser you will probably want to use the `--build` or `-b` option.

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

If you are compiling a build named `dev` then you are probably going
to want to include this script tag right before `</body>`.

```html
<script src="cljs-out/dev-main.js" type="text/javascript"></script>
```

To get started, place the following HTML code at `resources/public/index.html`:

```html
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
  </head>
  <body>
    <div id="app"></div>
    <script src="cljs-out/[build-id]-main.js" type="text/javascript"></script>
  </body>
</html>
```



