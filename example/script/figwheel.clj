(require
 '[figwheel-sidecar.repl-api :as ra]
 '[com.stuartsierra.component :as component]
 '[ring.component.jetty :refer [jetty-server]])

(def figwheel-config
  {:figwheel-options {}
   :build-ids ["example"]
   :all-builds
   [{ :id "example"
     :source-paths ["src"]

     :figwheel { :websocket-host "localhost"
                :on-jsload       "example.core/fig-reload"
                                        ; :debug true
                }
     :compiler { :main "example.core"
                :asset-path "js/out"
                :output-to "resources/public/js/example.js"
                :output-dir "resources/public/js/out"
                :source-map-timestamp true
                :libs ["libs_src" "libs_sscr/tweaky.js"]
                ;; :externs ["foreign/wowza-externs.js"]
                :foreign-libs [{:file "foreign/wowza.js"
                                :provides ["wowzacore"]}]
                ;; :recompile-dependents true
                :optimizations :none}}]})

(defrecord Figwheel [config]
    component/Lifecycle
  (start [comp]
    (ra/start-figwheel! comp)
    comp)
  (stop [comp]
    (ra/stop-figwheel!)
    comp))

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(def system
  (atom
   (component/system-map
    :app-server (jetty-server {:app {:handler handler}, :port 3000})
    :figwheel   (map->Figwheel figwheel-config))))

(defn start []
  (swap! system component/start))

(defn stop []
  (swap! system component/stop))

(defn repl []
  (ra/cljs-repl))

#_(start)
#_(ra/cljs-repl)
