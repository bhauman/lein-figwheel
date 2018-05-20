(ns figwheel.main.helper
  (:require
   [goog.dom :as gdom]
   [goog.net.XhrIo :as xhr]
   [goog.object :as gobj])
  (:import
   [goog Promise])
  )

#_(enable-console-print!)

(defn connected-signal []
  (.add (gobj/get (gdom/getElement "connect-status")
                  "classList")
        "connected"))

(defn get-content [url]
  (Promise.
   (fn [succ e]
     (xhr/send url
               (fn [resp]
                 (some-> resp
                         (gobj/get "currentTarget")
                         (.getResponseText)
                         succ))))))

(defn load-content [url div-id]
  (.then (get-content "com/bhauman/figwheel-helper/repl-host.html")
         (fn [content]
           (gdom/getElement div-id)
           (set! (.-innerHTML (gdom/getElement div-id))
                 content))))

(defn on-connect [e]
  (load-content "com/bhauman/figwheel-helper/repl-host.html" "main-content")
  (connected-signal))




(defonce initialize
  (do
    (.addEventListener js/document.body "figwheel.repl.connected"
                       on-connect)
    ))
