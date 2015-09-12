(ns example.server
  (:require
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.file-info :refer [wrap-file-info]]))

;; these handlers are only here to test various figwheel features and bugs

(defn handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "example server"})

(def static-server
  (-> handler
      (wrap-resource "public")
      (wrap-file-info)))

