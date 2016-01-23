(ns figwheel-sidecar.watching
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :refer [go-loop chan <! put! alts! timeout close!]]
   [hawk.core :as hawk]))

(defn single-files [files]
  (reduce (fn [acc f]
            (update-in acc
                       [(.getCanonicalPath (.getParentFile f))]
                       conj f))
          {} files))

(defn files-and-dirs [source-paths]
  (group-by #(if (.isDirectory %) :dirs :files)
            (map io/file source-paths)))

;; relies on canonical strings
(defn is-subdirectory [dir child]
  (.startsWith child (str dir java.io.File/separator)))

;;; we are going to have to throttle this
;; so that we can catch more than one file at a time

(defn take-until-timeout [in]
  (let [time-out (timeout 50)]
    (go-loop [collect []]
      (when-let [[v ch] (alts! [in time-out])]
        (if (= ch time-out)
          collect
          (recur (conj collect v)))))))

(defn default-hawk-options [hawk-options]
  (let [hawk-options (or hawk-options {})]
    (if (= (:watcher hawk-options)
           :polling)
      (merge {:sensitivity :high} hawk-options)
      hawk-options)))

(defn watch! [hawk-options source-paths callback]
  (let [hawk-options (default-hawk-options hawk-options)
        throttle-chan (chan)
        
        {:keys [files dirs]} (files-and-dirs source-paths)
        individual-file-map   (single-files files)
        canonical-source-dirs (set (map #(.getCanonicalPath %) dirs))

        source-paths (distinct
                      (concat (map str dirs)
                              (map #(.getParent %) files)))
        
        valid-file?   (fn [file]
                        (and file
                             (.isFile file)
                             (let [file-path (.getCanonicalPath file)
                                   n (.getName file)]
                               (and
                                (not= \. (first n))
                                (not= \# (first n))
                                (or
                                 ;; just skip this if
                                 ;; there are no individual-files
                                 (empty? individual-file-map)
                                 ;; if file is on a path that is already being watched we are cool
                                 (some #(is-subdirectory % file-path) canonical-source-dirs)
                                 ;; if not we need to see if its an individually watched file
                                 (when-let [acceptable-paths
                                            (get individual-file-map
                                                 (.getCanonicalPath (.getParentFile file)))]
                                   (some #(= (.getCanonicalPath %) file-path) acceptable-paths)))))))
        watcher (hawk/watch! hawk-options
                 [{:paths source-paths
                   :filter hawk/file?
                   :handler (fn [ctx e]
                              (put! throttle-chan e))}])]
    
    (go-loop []
      (when-let [v (<! throttle-chan)]
        (let [files (<! (take-until-timeout throttle-chan))]
          (when-let [result-files (not-empty (filter valid-file? (map :file (cons v files))))]
            (callback result-files)))
        (recur)))
    
    {:watcher watcher
     :throttle-chan throttle-chan}))

(defn stop! [{:keys [throttle-chan watcher]}]
  (hawk/stop! watcher)
  (Thread/sleep 200)
  (close! throttle-chan))
