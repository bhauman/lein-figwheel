(ns figwheel-sidecar.repl.controller
  (:require
    [figwheel-sidecar.repl.registry :as registry]
    [figwheel-sidecar.repl.driver :as driver]))

(defn eval-command-in-current-repl! [msg]
  (let [driver (registry/get-last-driver)
        {:keys [code input request-id]} msg]
    (driver/eval-external-command! driver request-id code input)))

(defn announce-ns-in-current-repl! [_msg]
  (let [driver (registry/get-last-driver)]
    (driver/announce-ns driver)))

; -- main handler -----------------------------------------------------------------------------------------------------------

(defn handle-msg [_server-state msg]
  (case (:command msg)
    "eval" (eval-command-in-current-repl! msg)
    "request-ns" (announce-ns-in-current-repl! msg)
    (println "Received unknown repl-driver message:" msg)))