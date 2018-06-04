(ns figwheel.server.ring
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [co.deps.ring-etag-middleware :as etag]
   [ring.middleware.cors :as cors]
   [ring.middleware.defaults]
   [ring.middleware.head :as head]
   [ring.middleware.stacktrace]
   [ring.middleware.not-modified :as not-modified]
   [ring.util.mime-type :as mime]
   [ring.util.response :refer [resource-response] :as response]))

;; ---------------------------------------------------
;; Async CORS
;; ---------------------------------------------------

(defn handle-async-cors [handler request respond' raise' access-control response-handler]
  (if (and (cors/preflight? request) (cors/allow-request? request access-control))
    (let [blank-response {:status 200
                          :headers {}
                          :body "preflight complete"}]
      (respond' (response-handler request access-control blank-response)))
    (if (cors/origin request)
      (if (cors/allow-request? request access-control)
        (handler request (fn [response]
                           (respond' (response-handler request access-control response)))
                 raise')
        (handler request respond' raise'))
      (handler request respond' raise'))))

(defn wrap-async-cors
  "Middleware that adds Cross-Origin Resource Sharing headers.
  (def handler
    (-> routes
        (wrap-cors
         :access-control-allow-origin #\"http://example.com\"
         :access-control-allow-methods [:get :put :post :delete])))
  "
  [handler & access-control]
  (let [access-control (cors/normalize-config access-control)]
    (fn [request respond' raise']
      (handle-async-cors handler request respond' raise' access-control cors/add-access-control))))

;; ---------------------------------------------------
;; Default Ring Stack
;; ---------------------------------------------------

;; File caching strategy:
;;
;; ClojureScript (as of March 2018) copies the last-modified date of Clojure
;; source files to the compiled JavaScript target files. Closure compiled
;; JavaScript (goog.base), it gets the time that it was compiled (i.e. now).
;;
;; Neither of these dates are particularly useful to use for caching. Closure
;; compiled JavaScript doesn't change from run to run, so caching based on
;; last modified date will not achieve as high a hit-rate as possible.
;; ClojureScript files can consume macros that change from run to run, but
;; will still get the same file modification date, so we would run the risk
;; of using stale cached files.
;;
;; Instead, we provide a checksum based ETag. This is based solely on the file
;; content, and so sidesteps both of the issues above. We remove the
;; Last-Modified header from the response to avoid it busting the browser cache
;; unnecessarily.

(defn wrap-no-cache
  "Add 'Cache-Control: no-cache' to responses.
   This allows the client to cache the response, but
   requires it to check with the server every time to make
   sure that the response is still valid, before using
   the locally cached file.

   This avoids stale files being served because of overzealous
   browser caching, while still speeding up load times by caching
   files."
  [handler]
  (fn [req]
    (some-> (handler req)
      (update :headers assoc
              "Cache-Control" "no-cache"))))

;; TODO send a fun default html from resources with inline images
(defn not-found [r]
  (response/content-type
   (response/not-found
   (str "<div><h1>Figwheel Server: Resource not found</h1>"
        "<h3><em>Keep on figwheelin' yep</em></h3></div>"))
   "text/html"))

(defn fix-index-mime-type [handler]
  (fn [request]
    (let [{:keys [body] :as res} (handler request)]
      (if (and body (instance? java.io.File body) (= "index.html" (.getName body)))
        (response/content-type res "text/html; charset=utf-8")
        res))))

(defn wrap-figwheel-defaults [ring-handler]
  (-> ring-handler
      (wrap-no-cache)
      (etag/wrap-file-etag)
      (not-modified/wrap-not-modified)
      ;; adding cors to support @font-face which has a strange cors error
      ;; INSECURE: don't use figwheel server as a production server :)
      ;; TODO not really sure if cors is needed
      (cors/wrap-cors
       :access-control-allow-origin #".*"
       :access-control-allow-methods [:head :options :get :put :post :delete :patch])))

(defn handle-first [& handlers]
  (fn [request]
    (first (map #(% request) (filter some? handlers)))))

(defn resource-root-index [handler root-s]
  (let [roots (if (coll? root-s) root-s [root-s])]
    (fn [request]
      (if (and (= "/" (:uri request))
               (#{:head :get} (:request-method request)))
        (if-let [resp (some-> (first
                               (map
                                #(resource-response
                                  "index.html" {:root %
                                                :allow-symlinks? true})
                                roots))
                              (update :headers dissoc "Last-Modified")
                              (response/content-type "text/html; charset=utf-8")
                              (head/head-response request))]
          resp
          (handler request))
        (handler request)))))


(defn best-guess-script-path [output-to]
  (when output-to
    (let [parts (string/split output-to #"[/\\]")]
      (when ((set parts) "public")
        (->> parts
             (split-with (complement #{"public"}))
             second
             (drop 1)
             (string/join "/"))))))

(defn index-html [{:keys [output-to body]}]
  (let [path  (best-guess-script-path output-to)
        body' (or body
                  (str "<p>Welcome to the Figwheel default index page.</p>"

                       "<p>You are seeing this because the webserver was unable to locate an index page for your application.</p>"

                       "<p>This page is currently hosting your REPL and application evaluation environment. "
                       "Validate the connection by typing <code>(js/alert&nbsp;\"Hello&nbsp;Figwheel!\")</code> in the REPL.</p>"
                       "<p>To provide your own custom page, place an <code>index.html</code> file on the server path.</p>"
                       "<pre>"
                       "&lt;!DOCTYPE html&gt;\n"
                       "&lt;html&gt;\n"
                       "  &lt;head&gt;\n"
                       "    &lt;meta charset=\"UTF-8\"&gt;\n"
                       "  &lt;/head&gt;\n"
                       "  &lt;body&gt;\n"
                       "    &lt;script src=\""
                       (if path path
                           (str "[correct-path-to "
                                (or output-to "main.js file")
                                "]"))
                       "\" type=\"text/javascript\"&gt;&lt;/script&gt;\n"
                       "  &lt;/body&gt;\n"
                       "&lt;/html&gt;\n"
                       "</pre>"
                       "</div></div>"))]
    (str
     "<!DOCTYPE html><html>"
     "<head>"
     "<meta charset=\"UTF-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" >"
     "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"cljs-logo-icon-32.png\"/>"
     "</head>"
     "<body>"
     "<div id=\"app\">"
     "<style>"
     "body { padding: 40px; margin: auto; max-width: 38em; "
     "font-family: \"Open Sans\", sans-serif; }"
     "code { color: #4165a2; font-size: 17px; }"
     "pre  { color: #4165a2; font-size: 15px; white-space: pre-wrap; }"
     "</style>"
     "<center>"
     "<svg width=\"200\" height=\"200\" version=\"1.1\" id=\"Layer_1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" x=\"0px\" y=\"0px\"
	 viewBox=\"0 0 428 426\" enable-background=\"new 0 0 428 426\" xml:space=\"preserve\">
<g>
	<path fill=\"#96CA4B\" d=\"M122,266.6c-12.7,0-22.3-3.7-28.9-11.1c-6.6-7.4-9.9-18-9.9-31.8c0-14.1,3.4-24.9,10.3-32.5
		s16.8-11.4,29.9-11.4c8.8,0,16.8,1.6,23.8,4.9l-5.4,14.3c-7.5-2.9-13.7-4.4-18.6-4.4c-14.5,0-21.7,9.6-21.7,28.8
		c0,9.4,1.8,16.4,5.4,21.2c3.6,4.7,8.9,7.1,15.9,7.1c7.9,0,15.4-2,22.5-5.9v15.5c-3.2,1.9-6.6,3.2-10.2,4
		C131.5,266.2,127.1,266.6,122,266.6z\"/>
	<path fill=\"#96CA4B\" d=\"M194.4,265.1h-17.8V147.3h17.8V265.1z\"/>
	<path fill=\"#5F7FBF\" d=\"M222.9,302.3c-5.3,0-9.8-0.6-13.3-1.9v-14.1c3.4,0.9,6.9,1.4,10.5,1.4c7.6,0,11.4-4.3,11.4-12.9v-93.5h17.8
		v94.7c0,8.6-2.3,15.2-6.8,19.6C237.9,300.1,231.4,302.3,222.9,302.3z M230.4,159.2c0-3.2,0.9-5.6,2.6-7.3c1.7-1.7,4.2-2.6,7.5-2.6
		c3.1,0,5.6,0.9,7.3,2.6c1.7,1.7,2.6,4.2,2.6,7.3c0,3-0.9,5.4-2.6,7.2c-1.7,1.7-4.2,2.6-7.3,2.6c-3.2,0-5.7-0.9-7.5-2.6
		C231.2,164.6,230.4,162.2,230.4,159.2z\"/>
	<path fill=\"#5F7FBF\" d=\"M342.5,241.3c0,8.2-3,14.4-8.9,18.8c-6,4.4-14.5,6.5-25.6,6.5c-11.2,0-20.1-1.7-26.9-5.1v-15.4
		c9.8,4.5,19,6.8,27.5,6.8c10.9,0,16.4-3.3,16.4-9.9c0-2.1-0.6-3.9-1.8-5.3c-1.2-1.4-3.2-2.9-6-4.4c-2.8-1.5-6.6-3.2-11.6-5.1
		c-9.6-3.7-16.2-7.5-19.6-11.2c-3.4-3.7-5.1-8.6-5.1-14.5c0-7.2,2.9-12.7,8.7-16.7c5.8-4,13.6-5.9,23.6-5.9c9.8,0,19.1,2,27.9,6
		l-5.8,13.4c-9-3.7-16.6-5.6-22.8-5.6c-9.4,0-14.1,2.7-14.1,8c0,2.6,1.2,4.8,3.7,6.7c2.4,1.8,7.8,4.3,16,7.5
		c6.9,2.7,11.9,5.1,15.1,7.3c3.1,2.2,5.4,4.8,7,7.7C341.7,233.7,342.5,237.2,342.5,241.3z\"/>
</g>
<path fill=\"#96CA4B\" stroke=\"#96CA4B\" stroke-width=\"6\" stroke-miterlimit=\"10\" d=\"M197,392.7c-91.2-8.1-163-85-163-178.3
	S105.8,44.3,197,36.2V16.1c-102.3,8.2-183,94-183,198.4s80.7,190.2,183,198.4V392.7z\"/>
<path fill=\"#5F7FBF\" stroke=\"#5F7FBF\" stroke-width=\"6\" stroke-miterlimit=\"10\" d=\"M229,16.1v20.1c91.2,8.1,163,85,163,178.3
	s-71.8,170.2-163,178.3v20.1c102.3-8.2,183-94,183-198.4S331.3,24.3,229,16.1z\"/>
</svg>"

     "</center>"
     "<!-- body start -->"
     body'
     "<!-- body end -->"
     (when (and output-to
                (.isFile (io/file output-to)))
       (str
        "<script type=\"text/javascript\">"
        (-> (slurp output-to)
            (string/replace #"<\/script" "<\\\\/script"))
        "</script>"))
     "</body></html>")))

(defn default-index-html [handler html]
  (fn [r]
    (let [res           (handler r)
          method-uri    ((juxt :request-method :uri) r)
          root-request? (= [:get "/"] method-uri)
          force-index? (= "figwheel-server-force-default-index=true"
                           (:query-string r))]
      (cond
        (and force-index? html)
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body html}
        (and root-request? (= 404 (:status res)) html)
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body html}
        :else res))))

(defn stack [ring-handler {:keys [::dev responses] :as config}]
  (let [{:keys [:co.deps.ring-etag-middleware/wrap-file-etag
                :ring.middleware.cors/wrap-cors
                :ring.middleware.not-modified/wrap-not-modified
                :ring.middleware.stacktrace/wrap-stacktrace]} dev]
    (cond-> (handle-first ring-handler not-found)
      (::resource-root-index dev) (resource-root-index (get-in config [:static :resources]))
      true                        (ring.middleware.defaults/wrap-defaults config)
      (dev ::fix-index-mime-type) fix-index-mime-type
      (dev ::default-index-html)  (default-index-html (::default-index-html dev))
      (dev ::wrap-no-cache)       wrap-no-cache
      wrap-file-etag              etag/wrap-file-etag
      wrap-not-modified           not-modified/wrap-not-modified
      wrap-cors                   (cors/wrap-cors
                                   :access-control-allow-origin #".*"
                                   :access-control-allow-methods
                                   [:head :options :get :put :post :delete :patch])
      wrap-stacktrace             ring.middleware.stacktrace/wrap-stacktrace)
    ;; to verify logic
    #_(cond-> []
        (::resource-root-index dev) (conj resource-root-index)
        true                        (conj ring.middleware.defaults/wrap-defaults)
        (dev ::fix-index-mime-type) (conj fix-index-mime-type)
        (dev ::wrap-no-cache)       (conj wrap-no-cache)
        wrap-file-etag              (conj etag/wrap-file-etag)
        wrap-not-modified           (conj not-modified/wrap-not-modified)
        wrap-cors                   (conj cors/wrap-cors)
        wrap-stacktrace             (conj ring.middleware.stacktrace/wrap-stacktrace))
    ))

(def default-options
  (-> ring.middleware.defaults/site-defaults
      (update ::dev #(merge {::fix-index-mime-type true
                             ::resource-root-index true
                             ::wrap-no-cache true
                             ;::default-index-html false
                             :ring.middleware.not-modified/wrap-not-modified true
                             :co.deps.ring-etag-middleware/wrap-file-etag true
                             :ring.middleware.cors/wrap-cors true
                             :ring.middleware.stacktrace/wrap-stacktrace true
                             }
                            %))
      (dissoc :security)
      (update :responses dissoc :not-modified-responses :absolute-redirects)))

(defn default-stack [handler options]
  (stack handler
         (merge-with
          (fn [& args]
            (if (every? #(or (nil? %) (map? %)) args)
              (apply merge args)
              (last args)))
          default-options
          options)))
