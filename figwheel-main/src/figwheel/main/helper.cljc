(ns figwheel.main.helper
  (:require
   #?@(:cljs
       [[goog.dom :as gdom]
       [goog.net.XhrIo :as xhr]
        [goog.object :as gobj]]
       :clj [[clojure.java.io :as io]])
   [clojure.string :as string]
   )
  #?(:cljs
     (:import
      [goog Promise])))

#?(:cljs
  (do

(defn connected-signal []
  (.add (gobj/get (gdom/getElement "connect-status")
                  "classList")
        "connected"))

(defn get-content [url]
  (Promise.
   (fn [succ e]
     (xhr/send url
               (fn [resp]
                 (some-> resp
                         (gobj/get "currentTarget")
                         (.getResponseText)
                         succ))))))

(defn load-content [url div-id]
  (.then (get-content url)
         (fn [content]
           (gdom/getElement div-id)
           (set! (.-innerHTML (gdom/getElement div-id))
                 content))))

(defn on-connect [e]
  (load-content "com/bhauman/figwheel-helper/repl-host.html" "main-content")
  (connected-signal))

(defonce initialize
  (.addEventListener js/document.body "figwheel.repl.connected"
                     on-connect))

))

#?(:clj
  (do

    (defn main-wrapper [{:keys [body output-to header] :as options}]
      (format
         "<!DOCTYPE html>
<html>
  <head>
    <link href=\"com/bhauman/figwheel/helper/css/style.css\" rel=\"stylesheet\" type=\"text/css\">
    <link href=\"com/bhauman/figwheel/helper/css/coderay.css\" rel=\"stylesheet\" type=\"text/css\">
  </head>
  <body>
    <div id=\"app\">
      <header>
        <div class=\"container\">
          <div id=\"connect-status\">
            <img class=\"logo\" width=\"60\" height=\"60\" src=\"com/bhauman/figwheel/helper/imgs/cljs-logo-120b.png\">
            <div class=\"connect-text\"><span id=\"connect-msg\"></span></div>
          </div>
          <div class=\"logo-text\">%s</div>
        </div>
      </header>
      <div class=\"container\">
        <aside>
          <a href=\"#getting-started\">Getting Started</a>
        </aside>
        <section id=\"main-content\">
        %s
        </section>
    </div>
    </div> <!-- end of app div -->
    %s
    <script type=\"text/javascript\">%s</script>
  </body>
</html>"
         (str header)
         (str body)
         (when-not (:dev-mode options)
           "<script src=\"com/bhauman/figwheel/helper.js\"></script>")
         (str
          (when (and output-to
                     (.isFile (io/file output-to)))
            (-> (slurp output-to)
                (string/replace #"<\/script" "<\\\\/script"))))))

(defn main-action [req cfg options]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (main-wrapper {:output-to (:output-to options)
                        :body nil
                        :dev-mode true
                        :header "REPL host page"})})

(defn dev-endpoint [req]
  (println "HERERE1")
  (let [method-uri ((juxt :request-method :uri) req)
        ;cfg (resolve 'figwheel.main/*config*)
        ]
    (println "HERERE2")
    (when (= method-uri [:get "/"])
      (main-action req nil nil #_(when cfg
                             {:output-to (get @cfg [:options :output-to])})))))

(defn middleware [handler cfg options]
  (fn [req]
    (let [method-uri ((juxt :request-method :uri) req)]
      (cond
        (= method-uri [:get "/"])
        (main-action req cfg options)
        :else (handler req)))))))
