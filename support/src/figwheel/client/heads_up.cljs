(ns figwheel.client.heads-up
  (:require
   [clojure.string :as string]
   [figwheel.client.socket :as socket]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(declare clear)
;; heads up display

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
  (let [cont-id "figwheel-heads-up-container"]
    (if-not (.querySelector js/document (str "#" cont-id))
      (let [el (node :div { :id cont-id
                           :style
                           (str "-webkit-transition: all 0.2s ease-in-out;"
                                "-moz-transition: all 0.2s ease-in-out;"
                                "-o-transition: all 0.2s ease-in-out;"
                                "transition: all 0.2s ease-in-out;"
                                "font-size: 13px;"
                                "background: url(https://s3.amazonaws.com/bhauman-blog-images/jira-logo-scaled.png) no-repeat 10px 10px;"
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
                                ) })]
        (set! (.-onclick el) heads-up-onclick-handler)
        (-> (.-body js/document)
            (.appendChild el)))
      (.getElementById js/document cont-id))))

(defn set-style [c st-map]
  (mapv
   (fn [[k v]]
     (aset (.-style c) (name k) v))
   st-map))

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
     (set! (.-innerHTML c ) msg)
     (set-style c (merge {
                          :paddingTop "10px"
                          :paddingBottom "10px"
                          :width "100%"
                          :minHeight "47px"
                          :height "auto"
                          :opacity "1.0" }
                         style))
     (<! (timeout 400)))))

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

(defn display-warning [msg]
  (display-heads-up {:backgroundColor "rgba(255, 220, 110, 0.95)" }
                    (str (close-link) (heading "Compile Warning") (format-line msg))))

(defn append-message [message]
  (let [c (ensure-container)]
     (set! (.-innerHTML c ) (str (.-innerHTML c) (format-line message)))))

(defn clear []
  (go
   (let [c (ensure-container)]
     (set-style c { :opacity "0.0" })
     (<! (timeout 300))
     (set-style c { :width "auto"
                    :height "0px"
                    :padding "0px 10px 0px 70px"
                    :borderRadius "0px"
                    :backgroundColor "transparent" })
     (<! (timeout 200))
     (set! (.-innerHTML c ) ""))))

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
