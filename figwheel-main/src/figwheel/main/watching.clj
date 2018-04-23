(ns figwheel.main.watching
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [hawk.core :as hawk]))

(def ^:dynamic *watcher* (atom {:watcher nil :watches {}}))

(defn alter-watches [{:keys [watcher watches]} f]
  (when watcher (hawk/stop! watcher))
  (let [watches (f watches)
        watcher (apply hawk/watch! (map vector (vals watches)))]
    {:watcher watcher :watches watches}))

(defn add-watch! [watch-key watch]
  (swap! *watcher* alter-watches #(assoc % watch-key watch)))

(defn remove-watch! [watch-key]
  (swap! *watcher* alter-watches #(dissoc % watch-key)))

(defn reset-watch! []
  (let [{:keys [watcher]} @*watcher*]
    (when watcher (hawk/stop! watcher))
    (reset! *watcher* {})))

(defn throttle [millis f]
  (fn [{:keys [collector] :as ctx} e]
    (let [collector (or collector (atom {}))
          {:keys [collecting? events]} (deref collector)]
      (if collecting?
        (swap! collector update :events (fnil conj []) e)
        (do
          (swap! collector assoc :collecting? true)
          (future (Thread/sleep millis)
                  (let [events (volatile! nil)]
                    (swap! collector
                           #(-> %
                                (assoc :collecting? false)
                                (update :events (fn [evts] (vreset! events evts) nil))))
                    (f (cons e @events))))))
      (assoc ctx :collector collector))))

(defn file-suffix [file]
  (last (string/split (.getName (io/file file)) #"\.")))

(defn suffix-filter [suffixes]
  (fn [_ {:keys [file]}]
    (and file
         (.isFile file)
         (not (.isHidden file))
         (suffixes (file-suffix file))
         (not (#{\. \#} (first (.getName file)))))))
