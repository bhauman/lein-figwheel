(ns exproj.core
  (:require
   [goog.events]))

(enable-console-print!)

(defn hello []
  "hello exproj")

(prn (hello))

;; stable reference
(defonce after-load (fn [e] (prn :after (.. e -data))))
;; idempotent with stable reference
(.addEventListener js/document.body "figwheel.after-load" after-load)

(defonce before-load (fn [e] (prn :before (.. e -data))))
;; idempotent with stable reference
(.addEventListener js/document.body "figwheel.before-load" before-load)

(defonce after-css-load (fn [e] (prn :after-css-load (.. e -data))))
;; idempotent with stable reference
(.addEventListener js/document.body "figwheel.after-css-load" after-css-load)

#_(d )
