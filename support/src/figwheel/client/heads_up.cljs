(ns figwheel.client.heads-up
  (:require
   [clojure.string :as string]
   [figwheel.client.socket :as socket]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(declare clear clojure-symbol-svg)

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
        (set! (.-innerHTML el) (str clojure-symbol-svg))
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
  (if-let [[f ln] (file-and-line-number msg)]
    (file-selector-div f ln msg)
    (str "<div>" msg "</div>")))

(defn display-error [formatted-messages]
  (let [[file-name file-line] (first (keep file-and-line-number formatted-messages))
        msg (apply str (map #(str "<div>" % "</div>") formatted-messages))]
    (display-heads-up {:backgroundColor "rgba(255, 161, 161, 0.95)"}
                      (str (close-link) (heading "Compile Error") (file-selector-div file-name file-line msg)))))

(defn display-system-warning [header msg]
  (display-heads-up {:backgroundColor "rgba(255, 220, 110, 0.95)" }
                    (str (close-link) (heading header) (format-line msg))))

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

(def clojure-symbol-svg
  "<?xml version='1.0' encoding='UTF-8' ?>
<!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN' 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd'>
<svg width='49px' height='49px' viewBox='0 0 100 99' version='1.1' xmlns='http://www.w3.org/2000/svg' style='position:absolute; top:9px; left: 10px;'>
<circle fill='rgba(255,255,255,0.5)' cx='49.75' cy='49.5' r='48.5'/>
<path fill='#5881d8' d=' M 39.30 6.22 C 51.71 3.11 65.45 5.64 75.83 13.16 C 88.68 22.10 96.12 38.22 94.43 53.80 C 93.66 60.11 89.40 66.01 83.37 68.24 C 79.21 69.97 74.64 69.78 70.23 69.80 C 80.77 59.67 81.41 41.33 71.45 30.60 C 63.60 21.32 49.75 18.52 38.65 23.16 C 31.27 18.80 21.83 18.68 14.27 22.69 C 20.65 14.79 29.32 8.56 39.30 6.22 Z' />
<path fill='#90b4fe' d=' M 42.93 26.99 C 48.49 25.50 54.55 25.62 59.79 28.14 C 68.71 32.19 74.61 42.14 73.41 51.94 C 72.85 58.64 68.92 64.53 63.81 68.69 C 59.57 66.71 57.53 62.30 55.66 58.30 C 50.76 48.12 50.23 36.02 42.93 26.99 Z' />
<path fill='#63b132' d=' M 12.30 33.30 C 17.11 28.49 24.33 26.90 30.91 28.06 C 25.22 33.49 21.44 41.03 21.46 48.99 C 21.11 58.97 26.58 68.76 35.08 73.92 C 43.28 79.06 53.95 79.28 62.66 75.29 C 70.37 77.57 78.52 77.36 86.31 75.57 C 80.05 84.00 70.94 90.35 60.69 92.84 C 48.02 96.03 34.00 93.24 23.56 85.37 C 12.16 77.09 5.12 63.11 5.44 49.00 C 5.15 43.06 8.22 37.42 12.30 33.30 Z' />
<path fill='#91dc47' d=' M 26.94 54.00 C 24.97 45.06 29.20 35.59 36.45 30.24 C 41.99 33.71 44.23 40.14 46.55 45.91 C 43.00 53.40 38.44 60.46 35.94 68.42 C 31.50 64.74 27.96 59.77 26.94 54.00 Z' />
<path fill='#91dc47' d=' M 41.97 71.80 C 41.46 64.27 45.31 57.52 48.11 50.80 C 50.40 58.13 51.84 66.19 57.18 72.06 C 52.17 73.37 46.93 73.26 41.97 71.80 Z' />
</svg>")
