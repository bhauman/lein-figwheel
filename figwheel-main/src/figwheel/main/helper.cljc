(ns figwheel.main.helper
  (:require
   #?@(:cljs
       [[goog.dom :as gdom]
       [goog.net.XhrIo :as xhr]
        [goog.object :as gobj]]
       :clj [[clojure.java.io :as io]
             [figwheel.server.ring :refer [best-guess-script-path]]])
   [clojure.string :as string])
  #?(:cljs
     (:import
      [goog Promise])))

#?(:cljs
  (do

(def sidebar-link-class "figwheel_main_content_link")
(def sidebar-focus-class "figwheel_main_content_link_focus")

(defn add-class [el klass]
  (.add (gobj/get el "classList") klass))

(defn remove-class [el klass]
  (.remove (gobj/get el "classList") klass))

(defn connected-signal []
  (add-class (gdom/getElement "connect-status") "connected"))

(defn on-disconnect []
  (remove-class (gdom/getElement "connect-status") "connected"))

(defonce resource-atom (atom {}))

(defn get-content [url]
  (Promise.
   (fn [succ e]
     (if-let [res (get @resource-atom url)]
       (succ res)
       (xhr/send url
                 (fn [resp]
                   (some-> resp
                           (gobj/get "currentTarget")
                           (.getResponseText)
                           (#(do (swap! resource-atom assoc url %)
                                 %))
                           succ)))))))

(defn load-content [url div-id]
  (.then (get-content url)
         (fn [content]
           (gdom/getElement div-id)
           (set! (.-innerHTML (gdom/getElement div-id))
                 content))))

(defn focus-anchor! [anchor-tag]
  (.forEach (gdom/getElementsByTagNameAndClass "a" sidebar-link-class)
            (fn [a]
              (remove-class a sidebar-focus-class)
              (when (= a anchor-tag)
                (add-class a sidebar-focus-class)))))

(defn init-sidebar-link-actions! []
  (.forEach (gdom/getElementsByTagNameAndClass "a" sidebar-link-class)
            (fn [a]
              ;; pre-fetch
              (when-let [content-loc (.-rel a)]
                (get-content content-loc)
                (.addEventListener
                 a "click"
                 (fn [e]
                   (.preventDefault e)
                   (focus-anchor! a)
                   (when-let [content-loc (.-rel (.-target e))]
                     (load-content content-loc "main-content"))))))))

(defn on-connect [e]
  (init-sidebar-link-actions!)
  (connected-signal))

(defonce initialize
  (do
    (.addEventListener js/document.body "figwheel.repl.connected"
                       on-connect)
    (.addEventListener js/document.body "figwheel.repl.disconnected"
                       on-disconnect)))
))

#?(:clj
  (do

    (defn main-wrapper [{:keys [body output-to header] :as options}]
      (format
         "<!DOCTYPE html>
<html>
  <head>
  </head>
  <body>
    <div id=\"app\">
      <link href=\"com/bhauman/figwheel/helper/css/style.css\" rel=\"stylesheet\" type=\"text/css\">
      <link href=\"com/bhauman/figwheel/helper/css/coderay.css\" rel=\"stylesheet\" type=\"text/css\">
      <header>
        <div class=\"container\">
          <div id=\"connect-status\">
            <img class=\"logo\" width=\"60\" height=\"60\" src=\"com/bhauman/figwheel/helper/imgs/cljs-logo-120b.png\">
            <div class=\"connect-text\"><span id=\"connect-msg\"></span></div>
          </div>
          <div class=\"logo-text\">%s</div>
        </div>
        <div class=\"container top-level-nav\">
             <a href=\"https://github.com/bhauman/lein-figwheel/tree/master/figwheel-main\" target=\"_blank\">Figwheel Main</a>
             <a href=\"https://clojurescript.org\" target=\"_blank\">CLJS</a>
             <a href=\"https://cljs.info\" target=\"_blank\">Cheatsheet</a>
             <a href=\"https://kanaka.github.io/clojurescript/web/synonym.html\" target=\"_blank\">Synonyms</a>
        </div>
      </header>

      <div class=\"container\">
        <aside>
          <a class=\"figwheel_main_content_link\" href=\"javascript:\" rel=\"figwheel/helper/welcome\">Welcome</a>
          <a class=\"figwheel_main_content_link\" href=\"javascript:\" rel=\"com/bhauman/figwheel/helper/content/creating_a_build_cli_tools.html\">Create a build</a>
          <a class=\"figwheel_main_content_link\" href=\"javascript:\" rel=\"com/bhauman/figwheel/helper/content/creating_a_build_lein.html\">Create a build (lein)</a>
          <a class=\"figwheel_main_content_link\" href=\"javascript:\" rel=\"com/bhauman/figwheel/helper/content/css_reloading.html\">Live Reload CSS</a>
        </aside>
        <section id=\"main-content\">
        %s
        </section>
      </div>
      <footer>
        <div class=\"container flex-column\">
          <div class=\"off-site-resources\">
             <h6>Figwheel Main</h6>
             <a href=\"https://github.com/bhauman/lein-figwheel/tree/master/figwheel-main\" target=\"_blank\">Figwheel Main Home / Readme</a>
             <a href=\"https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/doc/figwheel-main-options.md\" target=\"_blank\">Config Options</a>

          </div>
          <div class=\"off-site-resources\">
             <h6>Clojurescript</h6>
             <a href=\"https://clojurescript.org\" target=\"_blank\">ClojureScript Home</a>
             <a href=\"https://cljs.info\" target=\"_blank\">API Cheatsheet</a>
             <a href=\"https://kanaka.github.io/clojurescript/web/synonym.html\" target=\"_blank\">JavaScript Synonyms</a>
          </div>
          <div class=\"off-site-resources\">
             <h6>Community</h6>
             <a href=\"http://clojurians.slack.com\" target=\"_blank\">#clojurescript on Slack</a>
             <a href=\"http://clojurians.slack.com\" target=\"_blank\">#figwheel-main on Slack</a>
             <a href=\"http://clojurians.net\" target=\"_blank\">get Clojurians Slack invite</a>
             <a href=\"http://groups.google.com/group/clojurescript\" target=\"_blank\">ClojureScript Google Group</a>
             <a href=\"https://clojureverse.org/\" target=\"_blank\">ClojureVerse</a>
          </div>
        </div>
      </footer>
    %s
    </div> <!-- end of app div -->
    <script type=\"text/javascript\">%s</script>
  </body>
</html>"
         (str header)
         (str body)
         (str
          (when-not (:dev-mode options)
            "<script src=\"com/bhauman/figwheel/helper.js\"></script>"))
         (str
          (when (and output-to
                     (.isFile (io/file output-to)))
            (-> (slurp output-to)
                (string/replace #"<\/script" "<\\\\/script"))))))


(defn main-action [req options]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (main-wrapper options)})

(defn dev-endpoint [req]
  (let [method-uri ((juxt :request-method :uri) req)]
    (when (= method-uri [:get "/"])
      (main-action req {:output-to "target/public/cljs-out/helper-main.js"
                        :dev-mode true
                        :header "Dev Mode"
                        :body (slurp (io/resource "public/com/bhauman/figwheel/helper/content/repl_welcome.html"))}))))

(defn middleware [handler options]
  (fn [req]
    (let [method-uri ((juxt :request-method :uri) req)]
      (cond
        (and (= [:get "/figwheel/helper/welcome"] method-uri) (:body options))
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (:body options)}
        (= method-uri [:get "/"])
        (main-action req options)
        :else (handler req)))))

(defn missing-index-middleware [handler options]
  (fn [r]
    (let [method-uri ((juxt :request-method :uri) r)]
      (cond
        (and (= [:get "/figwheel/helper/welcome"] method-uri)
             (:body options))
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (:body options)}
        :else
        (let [res (handler r)]
          (if (and (= [:get "/"] method-uri)
                   (= 404 (:status res)))
            (main-action r options)
            res))))))

(defn default-index-code [output-to]
  (let [path (best-guess-script-path output-to)]
    (str
     "<div class=\"CodeRay\">"
     "<div class=\"code\">"
     "<pre>"
     "&lt;!DOCTYPE html&gt;\n"
     "&lt;html&gt;\n"
     "  &lt;head&gt;\n"
     "    &lt;meta charset=\"UTF-8\"&gt;\n"
     "  &lt;/head&gt;\n"
     "  &lt;body&gt;\n"
     "    &lt;div id=\"app\"&gt;\n"
     "    &lt;/div&gt;\n"
     "    &lt;script src=\""
     (if path path
         (str "[correct-path-to "
              (or output-to "main.js file")
              "]"))
     "\" type=\"text/javascript\"&gt;&lt;/script&gt;\n"
     "  &lt;/body&gt;\n"
     "&lt;/html&gt;\n"
     "</pre></div></div>")))

(defn missing-index [handler options]
  (missing-index-middleware
   handler
   (merge
    {:header "Figwheel Default Dev Page"
     :body (str
            (slurp (io/resource "public/com/bhauman/figwheel/helper/content/missing_index.html"))
            (default-index-code (:output-to options)))}
    options)))

(defn serve-only-middleware [handler options]
  (missing-index-middleware
   handler
   (merge
    {:header "Server Only Page"
     :body (slurp (io/resource "public/com/bhauman/figwheel/helper/content/server_only_welcome.html"))}
    options)))

)) ;; clj conditional reader end
