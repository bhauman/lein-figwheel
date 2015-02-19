(ns todo-server.core
  (:require
   [cljs.nodejs :as nodejs]
   [cljs.reader :refer [read-string]]
   [figwheel.client :as fw]))

(nodejs/enable-util-print!)

(defonce express (nodejs/require "express"))
(defonce serve-static (nodejs/require "serve-static"))
(defonce body-parser (nodejs/require "body-parser"))
(defonce http (nodejs/require "http"))

;; cheap edn middleware
(defn edn-body [req res next]
  (when (and (= (aget (.. req -headers) "content-type")
                "application/edn")
             (.-_body req))
    (set! (.-body req) (read-string (str (.-body req)))))
  (next))

(def app (express))

;; parse body with the raw-parser then parse the EDN
(. app (use (. body-parser (raw #js { :type "application/edn"}))))
(. app (use edn-body))

;; application starts here

(defonce db (atom { :last-id 0 :todos #{} }))

;; cheap in memory database
(defn insert [type-key insert-map]
  (let [{:keys [last-id] :as snapshot}
        (swap! db
               (fn [state]
                 (let [last-id    (:last-id state)
                       focus-type (get state type-key)]
                   (assoc state
                          :last-id (inc last-id)
                          type-key (set (conj focus-type (assoc insert-map :id (inc last-id))))))))]
    (first (filter #(= last-id (:id %)) (get snapshot type-key)))))

(defmulti transactor* :action)

(defmethod transactor* :create-todo [{:keys [value]}]
  (assoc (insert :todos (dissoc value :temp-id))
         :temp-id (:id value)))

(defn transact [req res]
  (. res (set "Content-Type" "application/edn"))
  (. res (send (pr-str (transactor* (.-body req))))))

(defn get-todos [req res]
  (. res (set "Content-Type" "application/edn"))
  (. res (send (pr-str (vec (:todos @db))))))

(. app (get "/todos" get-todos))

(. app (post "/transact" transact))

(. app (use (serve-static "resources/public" #js {:index "index.html"})))

(def -main (fn []
             (doto (.createServer http #(app %1 %2))
               (.listen 3000))))

(set! *main-cli-fn* -main)

(fw/start { :build-id "server" })
