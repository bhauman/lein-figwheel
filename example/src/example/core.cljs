(ns example.core
  (:require
   [sablono.core :as sab :include-macros true]
   [om.core :as om]
   [om.dom :as dom]
   [ankha.core :as ankha]))

(enable-console-print!)

(defn prevent [f]
  (fn [e]
    (.preventDefault e)
    (f e)))

(defn prevent->value [f]
  (prevent (fn [e]
             (f (.-value (.-target e))))))

(defonce app-state
  (atom { :todos
         [{ :id "todo_1"
           :content "buy milk"}
          { :id "todo_2"
            :content "buy car"}]
         :form-todo {} }))

(defn todo [{:keys [id content] :as huh}]
  (om/component
   (sab/html [:li
              [:div content]])))

(defn todo-list [todos owner]
  (om/component
   (sab/html [:ul (om/build-all todo todos)])))

(defn todo-form [data owner]
  (om/component
   (let [todos     (:todos data)
         form-todo (:form-todo data)]
     (sab/html
      [:form {:onSubmit
              (prevent
               #(do
                  (om/transact!
                   todos [] (fn [tds]
                              (conj tds
                                    (assoc form-todo
                                           :temp-id (name (gensym "temp-")))))
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
   (sab/html [:div
              [:h1 "Todos"]
              (om/build todo-list (:todos data))
              (om/build todo-form data)
              (inspect-data data)])))

(om/root widget app-state {:target (.getElementById js/document "app")
                           :tx-listen (fn [x _]
                                        (println "Transaction:")
                                        (prn x))})
