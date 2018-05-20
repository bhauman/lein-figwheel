(ns figwheel.main.helper-handler)




(defn handler [cfg options]
  (fn [req]


    ))

(defn wrapper [body output-to]
"<!DOCTYPE html>
<html>
  <head>
    <link href=\"css/style.css\" rel=\"stylesheet\" type=\"text/css\">
    <link href=\"css/coderay.css\" rel=\"stylesheet\" type=\"text/css\">
  </head>
  <body>
    <div id=\"app\">
      <header>
        <div class=\"container\">
          <div id=\"connect-status\">
            <img class=\"logo\" width=\"60\" height=\"60\" src=\"https://clojurescript.org/images/cljs-logo-120b.png\">
            <div class=\"connect-text\"><span id=\"connect-msg\"></span></div>
          </div>
          <div class=\"logo-text\">Figwheel REPL host page</div>
        </div>
      </header>
      <div class=\"container\">
        <aside>
          <a href=\"#getting-started\">Getting Started</a>
        </aside>
        <section id=\"main-content\">


        </section>
    </div>
    </div> <!-- end of app div -->
    <script src=\"cljs-out/helper-main.js\"></script>
  </body>
</html>")
