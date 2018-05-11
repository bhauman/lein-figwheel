(ns ^:figwheel-hooks exproj.core
  (:require
   [goog.events]
   [goog.object :as gobj]
   [clojure.string :as string]))

(enable-console-print!)

(defn hello []
  "hello  exproj")

(defn ^:after-load after-hook []
  (js/console.log "Called the AFTER hook!!!"))

(defn ^:before-load befor-hook [& args]
  (js/console.log "Called the before hook!!!"))

#_(d)

;; stable reference
#_(defonce after-load (fn [e] (prn :after (.. e -data))))
;; idempotent with stable reference
#_(.addEventListener js/document.body "figwheel.after-load" after-load)
#_(cljs.pprint/pprint (deref js/figwheel.core.state))
#_(defonce before-load (fn [e] (prn :before (.. e -data))))
;; idempotent with stable reference
#_(.addEventListener js/document.body "figwheel.before-load" before-load)



#_(defonce after-css-load (fn [e] (prn :after-css-load (.. e -data))))
;; idempotent with stable reference
#_(.addEventListener js/document.body "figwheel.after-css-load" after-css-load)

(defn -main [& args]
  (prn 35)
  35)

#_(defn)
#_(d d d d d d d)
