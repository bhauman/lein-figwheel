(ns user
  (:require
   [figwheel-sidecar.system :as fs]
   [com.stuartsierra.component :as component]
   [figwheel-sidecar.repl-api :as f]
   [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(def temp-config
  {:figwheel-options {:css-dirs ["resources/public/css"]
                                        ; :nrepl-port 7888
                      :cljs-build-fn "figwheel-sidecar.components.cljs-autobuild/figwheel-build-without-clj-reloading"
                      }
   :build-ids  ["example"]
   :all-builds (fs/get-project-builds)})

(defn start []
  (f/start-figwheel! temp-config))

(defn stop []
  (f/stop-figwheel!))

(defn reset []
  (stop)
  (refresh :after 'user/start))

