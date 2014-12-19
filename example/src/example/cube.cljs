(ns example.cube
  (:require
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop]]
   [example.other-macros :as o]))

;; A more advanced example using React and Sablono
;; you can edit any of this live

(o/logger (+ 1 2 23 3))

(defonce ex3-atom (atom {:rx 0 :ry 0 :rz 0}))

(defn css-transform [{:keys [rx ry rz tx ty tz]}]
  (let [trns (str "rotateX(" (or rx 0) "deg) "
                             "rotateY(" (or ry 0) "deg) "
                             "rotateZ(" (or rz 0) "deg) "
                             "translateX(" (or tx 0) "px) "
                             "translateY(" (or ty 0) "px) "
                             "translateZ(" (or tz 0) "px) "
                             )]
    { "-webkit-transform" trns
      "transform" trns }))

(defn side [trans side-css]
  [:div.side {:style (clj->js
                      (merge side-css
                                (css-transform trans)))}])


(defn cube [{:keys [size cube-css side-css]}]
  (let [translate (/ size 2)
        base-side-css { :backgroundColor "green"
                        :width  (str size "px")
                        :height (str size "px")
                        :position "absolute"
                        :top "0px"}
        base-cube-css { :width "100%"
                        :height "100%"
                        :-webkit-transform-style "preserve-3d"}
        cube-css (merge base-cube-css cube-css)
        side-css (merge base-side-css side-css)]
    [:div.cube {:style (clj->js cube-css)}
     (side { :ry 0 :tz translate} (assoc side-css :backgroundColor "blue"))
     (side { :ry 180 :tz translate} (assoc side-css :backgroundColor "blue"))
     (side { :ry 90 :tz translate} (assoc side-css :backgroundColor "green"))
     (side { :ry 270 :tz translate} (assoc side-css :backgroundColor "green"))
     (side { :rx 90 :tz translate} (assoc side-css :backgroundColor "red"))
     (side { :rx 270 :tz translate} (assoc side-css :backgroundColor "yellow"))     ]))

(defn slider [name k]
  [:div.slider-control
   [:p name]
   [:input {:type "range"
            :onChange (fn [e]
                        (swap! ex3-atom assoc k (.parseInt
                                                 js/window
                                                 (.-value (.-target e)))))
            :defaultValue (get @ex3-atom k)
            :min 0
            :max 359}]
   [:div (prn-str (get @ex3-atom k))]])

(defn main-template [state]
  [:div.example { :style {:float "left" :marginLeft "50px" }}
   [:h4 "3D cube"]
   [:div
    {:style (js-obj "position" "relative"
                      "width" "200px"
                      "height" "200px")}
    (cube { :size 200
            :cube-css (css-transform state)
           :side-css {  :width   "180px"
                        :height  "180px"
                        :opacity "0.5"
                        :border "10px solid #333"}})]
   [:div.controls {:style (clj->js {:marginTop "60px"})}
    (slider "rotateX" :rx)
    (slider "rotateY" :ry)
    (slider "rotateZ" :rz)]])


(defn render-ex-3 [state]
  (let [node (.getElementById js/document "example-3")]
    (.renderComponent js/React
                      (sab/html
                       [:div
                        (main-template state)])
                      node)))

;; the magic of react here is that this is reloadable by default!!
(render-ex-3 @ex3-atom)

;; atom watches are as well :)
(add-watch ex3-atom :renderer (fn [_ _ _ s] (render-ex-3 s)))

;; a better way for reloadability is to hook this into the :jsload-callback
;; this way non local changes will show up
;; if you want to use this comment out the lines above and hook this
;; reload callback in examples.core
(defn stop-and-start-ex3 []
  (render-ex-3 @ex3-atom)
  (add-watch ex3-atom :renderer (fn [_ _ _ s] (render-ex-3 s))))
