(ns figwheel-helper.core
  (:require
   [figwheel.client :as client]
   [cljs.reader :refer [read-string]]
   [goog.dom :as d]))

(enable-console-print!)

(def initial-messages
  '[{:build-id "dev",
    :msg-name :compile-failed,
    :exception-data
    {:file "cljs_src/figwheel_helper/core.cljs",
     :failed-compiling true,
     :column 1,
     :error-inline
     ([:code-line 11 "(println \"hey there\")"]
      [:error-in-code 12 "(defn)"]
      [:error-message
       nil
       "^--- Wrong number of args (0) passed to: core/defn--31810"]),
     :line 12,
     :class clojure.lang.ArityException,
     :analysis-exception
     {:cause
      {:cause nil,
       :class clojure.lang.ArityException,
       :message "Wrong number of args (0) passed to: core/defn--31810",
       :data nil},
      :class clojure.lang.ExceptionInfo,
      :message
      "Wrong number of args (0) passed to: core/defn--31810 at line 12 cljs_src/figwheel_helper/core.cljs",
      :data
      {:file "cljs_src/figwheel_helper/core.cljs",
       :column 1,
       :line 12,
       :tag :cljs/analysis-error}},
     :exception-data
     ({:cause
       {:cause
        {:cause nil,
         :class clojure.lang.ArityException,
         :message "Wrong number of args (0) passed to: core/defn--31810",
         :data nil},
        :class clojure.lang.ExceptionInfo,
        :message
        "Wrong number of args (0) passed to: core/defn--31810 at line 12 cljs_src/figwheel_helper/core.cljs",
        :data
        {:file "cljs_src/figwheel_helper/core.cljs",
         :column 1,
         :line 12,
         :tag :cljs/analysis-error}},
       :class clojure.lang.ExceptionInfo,
       :message "failed compiling file:cljs_src/figwheel_helper/core.cljs",
       :data {:file "cljs_src/figwheel_helper/core.cljs"}}
      {:cause
       {:cause nil,
        :class clojure.lang.ArityException,
        :message "Wrong number of args (0) passed to: core/defn--31810",
        :data nil},
       :class clojure.lang.ExceptionInfo,
       :message
       "Wrong number of args (0) passed to: core/defn--31810 at line 12 cljs_src/figwheel_helper/core.cljs",
       :data
       {:file "cljs_src/figwheel_helper/core.cljs",
        :column 1,
        :line 12,
        :tag :cljs/analysis-error}}
      {:cause nil,
       :class clojure.lang.ArityException,
       :message "Wrong number of args (0) passed to: core/defn--31810",
       :data nil}),
     :message "Wrong number of args (0) passed to: core/defn--31810"}}])

(defn close-bad-compile-screen []
  (d/removeNode
   (js/document.getElementById "figwheelFailScreen")))

(defn bad-compile-screen []
  (let [body (-> (d/getElementsByTagNameAndClass "body")
                 (aget 0))]
    (d/removeChildren body)
    (set! (.-innerHTML body) "")
    (d/append body
              (d/createDom
               "div"
               #js {:id "figwheelFailScreen"
                    :style (str "background-color: rgba(51, 51, 51, 0.7);"
                                "position: absolute;"
                                "width: 100vw;"
                                "height: 100vh;"
                                "top: 0px; left: 0px;"
                                "font-family: monospace")}
               
               (d/createDom
                "div"
                #js {:class "message"
                     :style (str "background-color: rgb(24,26,38); "
                                 "color: #FFF5DB;"
                                 "width: 100vw;"
                                 "margin: auto;"
                                 "margin-top: 10px;"
                                 "text-align: center; "
                                 "padding: 2px 0px;"
                                 "font-size: 13px;"
                                 "position: relative")}
                (d/createDom
                 "a"
                 #js {:onclick (fn [e]
                                 (.preventDefault e)
                                 (close-bad-compile-screen))
                      :href "javascript:"
                      :style "position: absolute; right: 10px; top: 10px; color: #666"}
                 "X")
                (d/createDom "h2" #js {:style "color: #FFF5DB"}
                             "Figwheel Says: Your code didn't compile.")
                (d/createDom "div" #js {:style "font-size: 12px"}
                             #_(d/createDom "h4" #js {}
                                          "Unable to compile " (:file exception-data))
                             #_(d/createDom "p" #js {}
                                          (:message exception-data))
                             (d/createDom "p" #js { :style "color: #D07D7D;"}
                                          "Keep trying. This page will auto-refresh when your code compiles successfully.")
                             #_(d/createDom "p" #js {:style "color: rgb(121,121,121);"}
                                          "This is a sentinel application that allows you to continue to get feedback from figwheel even though your application didn't compile.")
                             ))))))


#_(set! js/window.FIGWHEEL_CLIENT_CONFIGURATION (pr-str
                                               {:build-id "dev"
                                                :autoload false
                                                :initial-messages initial-messages}))

(defn fetch-data-from-env []
  (try
    (read-string js/window.FIGWHEEL_CLIENT_CONFIGURATION)
    (catch js/Error e
      (cljs.core/*print-err-fn* "Unable to load FIGWHEEL_CLIENT_CONFIGURATION from the environment")
      {:autoload false})))
   #_(defn)
(def console-intro-message
"Figwheel has compiled a temporary helper application to your :output-file.

The code in currently in your configured output file does not represent the code that you are trying
to compile.  

This temporary application is intended to help you continue to get feedback
from Figwheel until the build you are working on compiles correctly.

When your ClojureScript source code compiles correctly this helper
application will auto-reload and pick up your freshly compiled
CLojureScript program.")

(defn initialize-bad-helper-app []
  (let [config (fetch-data-from-env)]
    (println console-intro-message)
    (bad-compile-screen)
    (client/start config)
    (client/add-message-watch
     :listen-for-successful-compile
     (fn [{:keys [msg-name]}]
       (when (= msg-name :files-changed)
         (set! js/location.href js/location.href))))))

#_(prn (fetch-data-from-env))

(initialize-bad-helper-app)

#_(defn)

#_(println "hey there now now")
 #_(dddd d d d d)
      #_( defn)
