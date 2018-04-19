(ns exproj.server)

(defn handler [r]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "Server is working"})
