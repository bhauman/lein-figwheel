(ns example.core
  ;; NOTE: require implicitly loaded JavaScript file according namespace conventions
  (:require
   [example.something :as something] ;; implicit google closure
   [stuff.other :as other] ;; es6
   [stuff.hello :as hello] ;; commonjs
))

(enable-console-print!)

;; NOTE this funtion is defined in javascript
(println (something/hello))

;; NOTE this function is defined in an es6 JavaScript module
(println (other/sayHello))

;; NOTE this function is defined in a CommonJS JavaScript module
(println (hello/sayHello))



