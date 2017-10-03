(ns figwheel.env-config
  (:require
   [cljs.env]
   [clojure.string :as string]))

(def figwheel-client-hook-keys [:on-jsload
                                :before-jsload
                                :on-cssload
                                :on-message
                                :on-compile-fail
                                :on-compile-warning
                                :eval-fn])

(defn figwheel-client-config-from-env []
  (get-in (when-let [x cljs.env/*compiler*] @x)
          [:options :external-config :figwheel/config]
          {}))

(defn hook-name-to-js [hook-name]
  (symbol
   (str "js/"
        (string/join "." (string/split (str hook-name) #"/")))))

(defn try-jsreload-hook [k hook-name]
  ;; change hook to js form to avoid compile warnings when it doesn't
  ;; exist, these compile warnings are confusing and prevent code loading
  (let [hook-name' (hook-name-to-js hook-name)]
    `(fn [& x#]
       (if ~hook-name'
         (apply ~hook-name' x#)
         (figwheel.client.utils/log
          :debug (str "Figwheel: " ~k " hook '" '~hook-name "' is missing"))))))

(defn protect-reload-hooks [figwheel-config]
  (->> (select-keys figwheel-config figwheel-client-hook-keys)
       (map (fn [[k v]] [k (try-jsreload-hook k v)]))
       (into {})
       (merge figwheel-config)))

(defmacro external-tooling-config []
  (protect-reload-hooks (figwheel-client-config-from-env)))
