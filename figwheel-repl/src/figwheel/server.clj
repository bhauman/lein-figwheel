(ns figwheel.server
  (:require
   [clojure.string :as string]
   [ring.middleware.cors :as cors]
   [figwheel.server.jetty-websocket :as jtw]))

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
(defn ring-stack []
  (-> not-found
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
