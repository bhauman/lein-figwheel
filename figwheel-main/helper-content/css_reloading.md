# Live Reloading CSS

> **TLDR**: add a `:css-dirs ["resources/public/css"]` entry to the
> meta-data of your build `.cljs.edn` file or
> `figwheel-main.edn`

Figwheel will hot reload your CSS while you work on it. Figwheel just
needs to know where your CSS files are so it can watch and reload
them.

This will only work if the HTML file that is hosting your CLJS project
has included valid links to your CSS files.

You can normally place your CSS files anywhere below the
`resources/public` directory. For clarity we will place them at
`resources/public/css`.

Example CSS file at `resources/public/css/style.css`:
```css
/* hot stuff */
h1 { color: red; }
```

And as an example we can include it on the
`resources/public/index.html` page like so:

```html
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <!-- we are including the CSS here -->
    <link href="css/style.css" rel="stylesheet" type="text/css">
  </head>
  <body>
    <div id="app">
    </div>
    <script src="cljs-out/dev-main.js" type="text/javascript"></script>
  </body>
</html>
```

# Tell Figwheel to watch and reload CSS

You will use the `:css-dirs` config key to tell figwheel to which
directories to watch for CSS file changes.

You can do this one of two ways in the **build file** or in the
`figwheel-main.edn` file.

In the current example we are assuming a `dev.cljs.edn` build
file. You can add `:css-dirs` config to the meta-data in the build
file like so:

```clojure
^{:css-dirs ["resources/public/css"]}
{:main example.core}
```

Or you can set it for all builds and compiles in the `figwheel-main.edn`:

```clojure
{:css-dirs ["resources/public/css"]
 ;; rest of the config
 }
```

Then you restart your build:

```shell
cljs -m figwheel.main -b dev -r
```

Now you should be able to change the `style.css` file and see the changes
rendered live in your application.
