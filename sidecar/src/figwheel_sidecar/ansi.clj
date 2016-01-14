(ns figwheel-sidecar.ansi
  (:require [clojure.string :as str]))

;; this is stolen from puget as up to date ansi lib is not really available
;; https://github.com/greglook/puget/blob/develop/src/puget/color/ansi.clj

(def sgr-code
  "Map of symbols to numeric SGR (select graphic rendition) codes."
  {:none        0
   :bold        1
   :underline   3
   :blink       5
   :reverse     7
   :hidden      8
   :strike      9
   :black      30
   :red        31
   :green      32
   :yellow     33
   :blue       34
   :magenta    35
   :cyan       36
   :white      37
   :fg-256     38
   :fg-reset   39
   :bg-black   40
   :bg-red     41
   :bg-green   42
   :bg-yellow  43
   :bg-blue    44
   :bg-magenta 45
   :bg-cyan    46
   :bg-white   47
   :bg-256     48
   :bg-reset   49})

(defn esc
  "Returns an ANSI escope string which will apply the given collection of SGR
  codes."
  [codes]
  (let [codes (map sgr-code codes codes)
        codes (str/join \; codes)]
    (str \u001b \[ codes \m)))

(defn escape
  "Returns an ANSI escope string which will enact the given SGR codes."
  [& codes]
  (esc codes))

(defn sgr
  "Wraps the given string with SGR escapes to apply the given codes, then reset
  the graphics."
  [string & codes]
  (str (esc codes) string (escape :none)))

(defn strip
  "Removes color codes from the given string."
  [string]
  (str/replace string #"\u001b\[[0-9;]*[mK]" ""))

(defn color [text & codes]
  [:span [:pass (esc codes)] text [:pass (escape :none)]])
