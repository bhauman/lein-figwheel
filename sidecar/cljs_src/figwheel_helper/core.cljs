(ns figwheel-helper.core
  (:require [figwheel.client :as client]))

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


(client/start {:build-id "dev"
               :debug true
               :initial-messages initial-messages})

(println "hey there")
#_(defn)
