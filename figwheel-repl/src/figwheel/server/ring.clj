(ns figwheel.server.ring
  (:require
   [clojure.string :as string]
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

(defn stack [ring-handler {:keys [::dev responses] :as config}]
  (let [{:keys [:co.deps.ring-etag-middleware/wrap-file-etag
                :ring.middleware.cors/wrap-cors
                :ring.middleware.not-modified/wrap-not-modified
                :ring.middleware.stacktrace/wrap-stacktrace]} dev]
    (cond-> (handle-first ring-handler not-found)
      (::resource-root-index dev) (resource-root-index (get-in config [:static :resources]))
      true                        (ring.middleware.defaults/wrap-defaults config)
      (dev ::fix-index-mime-type) fix-index-mime-type
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
