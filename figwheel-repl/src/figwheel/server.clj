(ns figwheel.server
  (:require
   [clojure.string :as string]
   [ring.middleware.cors :as cors]
   [figwheel.server.jetty-websocket :as jtw]))

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

#_((wrap-async-cors
 (fn [request resp' rais']
   (resp' {:status 200
           :headers {}
           :body "result"})
   )
 :access-control-allow-origin #".*"
 :access-control-allow-methods [:head :options :get :put :post :delete :patch])
 request
 (fn [res]
   (prn res))
 identity)

;; ---------------------------------------------------
;; Default server
;; ---------------------------------------------------

;; TODO send a fun default html from resources with inline images
(defn not-found [r]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "Figwheel Server: Route Not found"})

;; TODO have to fill this out with default server functionality
;; TODO have to consider the cleint supplying this function and or a
;; ring handler to run inside of it
(defn ring-stack [& [ring-middleware]]
  (-> not-found
      ((or ring-middleware (fn [h] (fn [r] (h r)))))
      #_(http-polling-middleware)
      (cors/wrap-cors
       :access-control-allow-origin #".*"
       :access-control-allow-methods [:head :options :get :put :post :delete :patch])))

;; TODO this could be smarter and introspect the environment
;; to see what server is available??
(defn run-server [handler options]
  (jtw/run-jetty
   handler
   (cond-> options
     (:figwheel.repl/abstract-websocket-connection options)
     ;; TODO make figwheel-path configurable
     (assoc-in [:websockets "/figwheel-connect"]
               (jtw/adapt-figwheel-ws
                (:figwheel.repl/abstract-websocket-connection options))))))



(comment
  (def request-data
    {:ssl-client-cert nil,
    :protocol "HTTP/1.1",
    :remote-addr "0:0:0:0:0:0:0:1",
    :headers
    {"cache-control" "max-age=0",
     "accept"
     "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
     "upgrade-insecure-requests" "1",
     "connection" "keep-alive",
     "user-agent"
     "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/64.0.3282.186 Safari/537.36",
     "host" "localhost:9500",
     "accept-encoding" "gzip, deflate, br",
     "accept-language" "en-US,en;q=0.9,fr;q=0.8,la;q=0.7"},
    :server-port 9500,
     :content-length nil,
     :content-type nil,
    :character-encoding nil,
    :uri "/figwheel-connect",
     :server-name "localhost",
    :query-string nil,
     :body "hey"
     :scheme :http,
     :request-method :get})


  )
