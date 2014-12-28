(ns example.core
  (:require
   [figwheel.client :as fw]
   [example.cube]
   [crate.core])
  (:require-macros
   [example.macros :as m]))

(enable-console-print!)

;; IMPORTANT!!!
;; you must call this function to start the listener that reloads
;; the compiled javascript

(declare ex2-restart)

(fw/start {
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :on-jsload (fn []
               (ex2-restart)
               ;; this is a better way to reload the cube example
               ;; which will reload even for non-local changes
               ;; (example.cube/stop-and-start-ex3)
               )
  })

(m/log (+ 1 2 2 3 4 5))


;; When you are writing reloadable code you have to protect things
;; that you don't want defined over and over.

;; go ahead and change this print statement and hit save.
;; You should see the changed statement printed out in the console of
;; your web inspector.

(println "This is a reloaded print statement: modify me now.")

;; Example 1:  simple crate based app

;; Try editing the example below and watch how the live reloading
;; responds.  This is a rough example meant to quickly demonstrate
;; the reloading behavior. Notice that state is maintained and your
;; code updates are reflected in the browser.

;; define atom once
(defonce ex1-atom (atom {:r 0 :g 0 :b 0}))

(defn ex1-template [{:keys [r g b]}]
  [:div.example {:style "float:left;"}
   [:h4 "Example 1"]
   [:div {:style (str "width: 200px; height: 200px; background-color: rgb("r ","g "," b ")")}]
   [:input.example-color-select {:type "range" :min 0 :max 255 :value r :data-color "r"}]
   [:div "red: " r]   
   [:input.example-color-select {:type "range" :min 0 :max 255 :value g :data-color "g"}]
   [:div "green: " g]
   [:input.example-color-select {:type "range" :min 0 :max 255 :value b :data-color "b"}]   
   [:div "this is a blue: " b]])

(defn ex1-render [v]
  (.html (js/$ "#example-1") (crate.core/html 
                              (ex1-template v))))

;; we can bash in a new listener on reload no problem
(add-watch ex1-atom :atom-listen (fn [_ _ _ n] (ex1-render n)))

;; we can render on reload no problem
(ex1-render @ex1-atom)

;; you can try to modify this function but you will see no changes as
;; you edit it
;; this is because it's refered to from the defonce block below
(defn ex-1-callback [e]
  (let [v (.-value (.-currentTarget e))
        color (keyword (.-color (.data (js/$ (.-currentTarget e)))))]
    (swap! ex1-atom assoc color v)))

;; add listeners once
(defonce example-1-listeners
  (.on (js/$ "#example-1") "change" ".example-color-select" ex-1-callback))

;; Example 2: simple crate based app with lifecyle management.

;; With this example we implement a crude lifecycle that makes it
;; pretty easy to edit almost all of the code in the example except
;; for initial state of the ex2-atom. Take note that we are hooking
;; the reload function into the :jsload-callback in the watcher at the
;; bottom of the page

(defonce ex2-atom (atom {:r 0 :g 0 :b 0}))

(defn ex2-template [{:keys [r g b]}]
  [:div.example {:style "float: left; margin-left: 50px"}
   [:h4 "Example 2"]
   [:div {:style (str "width: 200px; height: 200px; background-color: rgb("r ","g "," b ")")}]
   [:input.example-color-select {:type "range" :min 0 :max 255 :value r :data-color "r"}]
   [:div "red: " r]   
   [:input.example-color-select {:type "range" :min 0 :max 255 :value g :data-color "g"}]
   [:div "this is greener: " g]      
   [:input.example-color-select {:type "range" :min 0 :max 255 :value b :data-color "b"}]   
   [:div "this is blue: " b]])

(defn ex-2-callback [e]
  (let [v (.-value (.-currentTarget e))
        color (keyword (.-color (.data (js/$ (.-currentTarget e)))))]
    (swap! ex2-atom assoc color v)))

(defn ex2-render [v]
  (.html (js/$ "#example-2") (crate.core/html (ex2-template v))))

;; crude lifecycle management
(defn ex2-start []
  (add-watch ex2-atom :atom-listen (fn [_ _ _ n]
                                     (ex2-render n)))
  (ex2-render @ex2-atom) ;; initial render
  (.on (js/$ "#example-2") "change" ".example-color-select" ex-2-callback))

(defn ex2-stop []
  (remove-watch ex2-atom :atom-listen)
  (.off (js/$ "#example-2") "change" ".example-color-select"))

(defn ex2-restart []
  (ex2-stop)
  (ex2-start))

;; start the app once
(defonce start-ex2 (ex2-start))
