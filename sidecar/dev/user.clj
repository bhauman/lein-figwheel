(ns user
  (:require
   [figwheel-sidecar.repl-api :as repl-api]))

(defn start []
  (repl-api/start-figwheel!)
  (repl-api/cljs-repl "dev"))
