(ns figwheel.client.heads-up
  (:require
   [clojure.string :as string]
   [figwheel.client.socket :as socket]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async]
   [goog.string])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(declare clear cljs-logo-svg)

;; cheap hiccup
(defn node [t attrs & children]
     (let [e (.createElement js/document (name t))]
       (doseq [k (keys attrs)] (.setAttribute e (name k) (get attrs k)))
       (doseq [ch children] (.appendChild e ch)) ;; children
       e))

(defmulti heads-up-event-dispatch (fn [dataset] (.-figwheelEvent dataset)))
(defmethod heads-up-event-dispatch :default [_]  {})

(defmethod heads-up-event-dispatch "file-selected" [dataset]
  (socket/send! {:figwheel-event "file-selected"
                 :file-name (.-fileName dataset)
                 :file-line (.-fileLine dataset)}))

(defmethod heads-up-event-dispatch "close-heads-up" [dataset] (clear))

(defn ancestor-nodes [el]
  (iterate (fn [e] (.-parentNode e)) el))

(defn get-dataset [el]
  (first (keep (fn [x] (when (.. x -dataset -figwheelEvent) (.. x -dataset)))
               (take 4 (ancestor-nodes el)))))

(defn heads-up-onclick-handler [event]
  (let [dataset (get-dataset (.. event -target))]
    (.preventDefault event)
    (when dataset
      (heads-up-event-dispatch dataset))))

(defn ensure-container []
  (let [cont-id "figwheel-heads-up-container"
        content-id "figwheel-heads-up-content-area"]
    (if-not (.querySelector js/document (str "#" cont-id))
      (let [el (node :div { :id cont-id
                           :style
                           (str "-webkit-transition: all 0.2s ease-in-out;"
                                "-moz-transition: all 0.2s ease-in-out;"
                                "-o-transition: all 0.2s ease-in-out;"
                                "transition: all 0.2s ease-in-out;"
                                "font-size: 13px;"
                                "border-top: 1px solid #f5f5f5;"
                                "box-shadow: 0px 0px 1px #aaaaaa;"
                                "line-height: 18px;"
                                "color: #333;"
                                "font-family: monospace;"
                                "padding: 0px 10px 0px 70px;"
                                "position: fixed;"
                                "bottom: 0px;"
                                "left: 0px;"
                                "height: 0px;"
                                "opacity: 0.0;"
                                "box-sizing: border-box;"
                                "z-index: 10000;"
                                ) })]
        (set! (.-onclick el) heads-up-onclick-handler)
        (set! (.-innerHTML el) cljs-logo-svg)
        (.appendChild el (node :div {:id content-id}))
        (-> (.-body js/document)
            (.appendChild el))))
    { :container-el    (.getElementById js/document cont-id)
      :content-area-el (.getElementById js/document content-id) }
      ))

(defn set-style! [{:keys [container-el]} st-map]
  (mapv
   (fn [[k v]]
     (aset (.-style container-el) (name k) v))
   st-map))

(defn set-content! [{:keys [content-area-el] :as c} dom-str]
  (set! (.-innerHTML content-area-el) dom-str))

(defn get-content [{:keys [content-area-el]}]
  (.-innerHTML content-area-el))

(defn close-link []
  (str "<a style=\""
      "float: right;"
      "font-size: 18px;"
      "text-decoration: none;"
      "text-align: right;"
      "width: 30px;"
      "height: 30px;"
      "color: rgba(84,84,84, 0.5);"
      "\" href=\"#\"  data-figwheel-event=\"close-heads-up\">"
      "x"
      "</a>"))

(defn display-heads-up [style msg]
  (go
   (let [c (ensure-container)]
     (set-style! c (merge {
                          :paddingTop "10px"
                          :paddingBottom "10px"
                          :width "100%"
                          :minHeight "68px"
                          :opacity "1.0" }
                          style))
     (set-content! c msg)
     (<! (timeout 300))
     (set-style! c {:height "auto"}))))


(defn heading [s]
  (str"<div style=\""
      "font-size: 26px;"
      "line-height: 26px;"
      "margin-bottom: 2px;"
      "padding-top: 1px;"
      "\">"
      s "</div>"))

(defn file-and-line-number [msg]
  (when (re-matches #".*at\sline.*" msg)
    (take 2 (reverse (string/split msg " ")))))

(defn file-selector-div [file-name line-number msg]
  (str "<div data-figwheel-event=\"file-selected\" data-file-name=\""
       file-name "\" data-file-line=\"" line-number
       "\">" msg "</div>"))

(defn format-line [msg]
  (let [msg (goog.string/htmlEscape msg)]
    (if-let [[f ln] (file-and-line-number msg)]
      (file-selector-div f ln msg)
      (str "<div>" msg "</div>"))))

(defn display-error [formatted-messages cause]
  (let [[file-name file-line file-column]
        (if cause
          [(:file cause) (:line cause) (:column cause)]
          (first (keep file-and-line-number formatted-messages)))
        msg (apply str (map #(str "<div>" (goog.string/htmlEscape %)  "</div>") formatted-messages))]
    (display-heads-up {:backgroundColor "rgba(255, 161, 161, 0.95)"}
                      (str (close-link) (heading "Compile Error")
                           (file-selector-div file-name (or file-line (and cause (:line cause)))
                                              (str msg
                                                   (if cause
                                                     (str "Error on file "
                                                          (goog.string/htmlEscape (:file cause))
                                                          ", line "
                                                          (:line cause)
                                                          ", column " (:column cause))
                                                     "")))))))

(defn display-system-warning [header msg]
  (display-heads-up {:backgroundColor "rgba(255, 220, 110, 0.95)" }
                    (str (close-link) (heading header)
                         (format-line msg))))

(defn display-warning [msg]
  (display-system-warning "Compile Warning" msg))

(defn append-message [message]
  (let [{:keys [content-area-el]} (ensure-container)
        el (.createElement js/document "div")]
    (set! (.-innerHTML el) (format-line message))
    (.appendChild content-area-el el)))

(defn clear []
  (go
   (let [c (ensure-container)]
     (set-style! c { :opacity "0.0" })
     (<! (timeout 300))
     (set-style! c { :width "auto"
                    :height "0px"
                    :minHeight "0px"
                    :padding "0px 10px 0px 70px"
                    :borderRadius "0px"
                    :backgroundColor "transparent" })
     (<! (timeout 200))
     (set-content! c ""))))

(defn display-loaded-start []
  (display-heads-up {:backgroundColor "rgba(211,234,172,1.0)"
                     :width "68px"
                     :height "68px"
                     :paddingLeft "0px"
                     :paddingRight "0px"
                     :borderRadius "35px" } ""))

(defn flash-loaded []
  (go
   (<! (display-loaded-start))
   (<! (timeout 400))
   (<! (clear))))

(def cljs-logo-svg
  "<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN' 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd'>
<svg width='49px' height='49px' style='position:absolute; top:9px; left: 10px;' version='1.1'
  xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' x='0px' y='0px'
  viewBox='0 0 428 428' enable-background='new 0 0 428 428' xml:space='preserve'>
<circle fill='#fff' cx='213' cy='214' r='213' />
<g>
<path fill='#96CA4B' d='M122,266.6c-12.7,0-22.3-3.7-28.9-11.1c-6.6-7.4-9.9-18-9.9-31.8c0-14.1,3.4-24.9,10.3-32.5
  s16.8-11.4,29.9-11.4c8.8,0,16.8,1.6,23.8,4.9l-5.4,14.3c-7.5-2.9-13.7-4.4-18.6-4.4c-14.5,0-21.7,9.6-21.7,28.8
  c0,9.4,1.8,16.4,5.4,21.2c3.6,4.7,8.9,7.1,15.9,7.1c7.9,0,15.4-2,22.5-5.9v15.5c-3.2,1.9-6.6,3.2-10.2,4
  C131.5,266.2,127.1,266.6,122,266.6z'/>
<path fill='#96CA4B' d='M194.4,265.1h-17.8V147.3h17.8V265.1z'/>
<path fill='#5F7FBF' d='M222.9,302.3c-5.3,0-9.8-0.6-13.3-1.9v-14.1c3.4,0.9,6.9,1.4,10.5,1.4c7.6,0,11.4-4.3,11.4-12.9v-93.5h17.8
  v94.7c0,8.6-2.3,15.2-6.8,19.6C237.9,300.1,231.4,302.3,222.9,302.3z M230.4,159.2c0-3.2,0.9-5.6,2.6-7.3c1.7-1.7,4.2-2.6,7.5-2.6
  c3.1,0,5.6,0.9,7.3,2.6c1.7,1.7,2.6,4.2,2.6,7.3c0,3-0.9,5.4-2.6,7.2c-1.7,1.7-4.2,2.6-7.3,2.6c-3.2,0-5.7-0.9-7.5-2.6
  C231.2,164.6,230.4,162.2,230.4,159.2z'/>
<path fill='#5F7FBF' d='M342.5,241.3c0,8.2-3,14.4-8.9,18.8c-6,4.4-14.5,6.5-25.6,6.5c-11.2,0-20.1-1.7-26.9-5.1v-15.4
  c9.8,4.5,19,6.8,27.5,6.8c10.9,0,16.4-3.3,16.4-9.9c0-2.1-0.6-3.9-1.8-5.3c-1.2-1.4-3.2-2.9-6-4.4c-2.8-1.5-6.6-3.2-11.6-5.1
  c-9.6-3.7-16.2-7.5-19.6-11.2c-3.4-3.7-5.1-8.6-5.1-14.5c0-7.2,2.9-12.7,8.7-16.7c5.8-4,13.6-5.9,23.6-5.9c9.8,0,19.1,2,27.9,6
  l-5.8,13.4c-9-3.7-16.6-5.6-22.8-5.6c-9.4,0-14.1,2.7-14.1,8c0,2.6,1.2,4.8,3.7,6.7c2.4,1.8,7.8,4.3,16,7.5
  c6.9,2.7,11.9,5.1,15.1,7.3c3.1,2.2,5.4,4.8,7,7.7C341.7,233.7,342.5,237.2,342.5,241.3z'/>
</g>
<path fill='#96CA4B' stroke='#96CA4B' stroke-width='6' stroke-miterlimit='10' d='M197,392.7c-91.2-8.1-163-85-163-178.3
  S105.8,44.3,197,36.2V16.1c-102.3,8.2-183,94-183,198.4s80.7,190.2,183,198.4V392.7z'/>
<path fill='#5F7FBF' stroke='#5F7FBF' stroke-width='6' stroke-miterlimit='10' d='M229,16.1v20.1c91.2,8.1,163,85,163,178.3
  s-71.8,170.2-163,178.3v20.1c102.3-8.2,183-94,183-198.4S331.3,24.3,229,16.1z'/>
</svg>")
