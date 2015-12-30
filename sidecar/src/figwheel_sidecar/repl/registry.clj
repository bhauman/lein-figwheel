(ns figwheel-sidecar.repl.registry)

(def drivers (atom {}))
(def last-registered-driver-id (volatile! nil))

; -- driver management -----------------------------------------------------------------------------------------------------

(defn register-driver! [id driver]
  {:pre [id]}
  (swap! drivers assoc id driver)
  (vreset! last-registered-driver-id id)
  driver)

(defn unregister-driver! [id]
  {:pre [id]}
  (swap! drivers dissoc id))

(defn get-driver [id]
  {:pre [id]}
  (id @drivers))

(defn get-last-driver []
  {:pre [@last-registered-driver-id]}
  (get-driver @last-registered-driver-id))