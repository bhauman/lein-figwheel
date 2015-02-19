(ns ^:figwheel-always example.core
  (:require
   [sablono.core :as sab :include-macros true]
   [om.core :as om]
   [om.dom :as dom]
   [ankha.core :as ankha]
   [example.style :as style]
   [cljs.reader :refer [read-string]]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<!]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defn prevent [f]
  (fn [e]
    (.preventDefault e)
    (f e)))

(defn prevent->value [f]
  (prevent (fn [e]
             (f (.-value (.-target e))))))

(defonce app-state
  (atom { :todos []
          :form-todo {} }))

(defn todos* []
  (om/ref-cursor (:todos (om/root-cursor app-state))))

;; transactions

(defn add-todo [form-todo todos]
  (vec
   (conj todos
         (assoc form-todo
                :id (name (gensym "temp-"))
                :created-at (js/Date.)))))

(defn update-todo [id data todos]
  (mapv
   (fn [td]
     (if (= (:id td) id)
       (merge td data)
       td))
   todos))

(defn delete-todo [id todos]
  (vec (filter #(not= (:id %) id) todos)))

(defn update-todo! [todos id data]
  (om/transact! todos []
                (partial update-todo id data)
                :update-todo))

(defn delete-todo! [todos id]
  (om/transact! todos [] (partial delete-todo id) :delete-todo))

(defn todo [{:keys [id content completed] :as huh} owner]
  (om/component
   (let [todos (om/observe owner (todos*))]
     (sab/html [:li 
                [:div 
                 (if completed
                   [:a {:href "#"
                        :style style/done-button
                        :onClick (prevent #(delete-todo! todos id))}
                    "delete"]
                   [:a {:href "#"
                        :style style/done-button
                        :onClick (prevent
                                  #(update-todo! todos id {:completed true}))}
                    "done"])
                 [:span {:style (if completed style/completed-todo {})}
                  content]]]))))

(defn todo-list [todos owner]
  (om/component
   (sab/html [:ul {:style style/todo-list} (om/build-all todo todos)])))

(defn todo-form [data owner]
  (om/component
   (let [todos     (:todos data)
         form-todo (:form-todo data)]
     (sab/html
      [:form {:onSubmit
              (prevent
               #(do
                  (om/transact!
                   todos []
                   (partial add-todo form-todo)
                   :create-todo)
                  (om/update! form-todo {})))}
       [:input {:type "text"
                :placeholder "Todo"
                :value (:content form-todo)
                :onChange (prevent->value
                           #(om/update! form-todo :content %))}]]))))

(defn inspect-data [data]
  (sab/html
   [:div.ankha {:style {:marginTop "50px"}}
    (om/build ankha/inspector data)]))

(defn widget [data owner]
  (om/component
   (let [todos-parts (group-by :completed (:todos data))
         todos       (get todos-parts nil)
         completed-todos (get todos-parts true)]
     (sab/html [:div
                [:h1 "Todos"]
                (om/build todo-form data)
                (om/build todo-list todos)
                (when (not-empty completed-todos)
                  (sab/html
                   [:div [:h4 "Completed"]
                    (om/build todo-list completed-todos)]))
                (inspect-data data)]))))

(defmulti remote-transact :tag)

(defmethod remote-transact :default [_])

(defmethod remote-transact :create-todo [{:keys [old-value new-value]}]
  (when (= 1 (- (count new-value) (count old-value)))
    (go
      (let [res (<! (http/post
                     "/transact"
                     {:edn-params {:action :create-todo
                                   :value  (last new-value)}}))]
        (when (:success res)
          (let [todo (:body res)]
            (swap! app-state update-in [:todos]
                   (partial update-todo
                            (:temp-id todo)
                            (dissoc todo :temp-id)))))))))

(defmethod remote-transact :update-todo [{:keys [old-value new-value]}]
  (let [[_ new] (first (filter
                        #(not= %1 %2)
                        (map vector old-value new-value)))]
    (prn new)
    #_(go
      (let [res (<! (http/post
                     "/transact"
                     {:edn-params {:action  :update-todo
                                    :value  (pr-str new)}}))]
        (when (:success res)
          (let [todo (read-string (:body res))]
            (swap! app-state update-in [:todos]
                   (partial update-todo (:id todo) todo))))))))

(defn get-todos []
  (go
    (let [data (<! (http/get "/todos"))]
      (if (:success data)
        (vec (-> data :body))
        []))))

(defonce init-data
  (go
    (let [todos (<! (get-todos))]
      (swap! app-state assoc :todos todos))))

(om/root widget app-state {:target (.getElementById js/document "app")
                           :tx-listen (fn [x _]
                                        (println "Transaction:")
                                        (prn x)
                                        (remote-transact x))})
