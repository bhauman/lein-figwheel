(ns figwheel-sidecar.watching
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :refer [go-loop]])
  (:import
   [java.nio.file Path Paths Files StandardWatchEventKinds WatchKey
    WatchEvent FileVisitor FileVisitResult]
   [com.sun.nio.file SensitivityWatchEventModifier]
   [java.util.concurrent TimeUnit]))

;; general watcher that can watch individual files
;; this is derived from the clojurescript watcher

;; would be nice to have this throttled so it would collect changes
;; perhaps waiting for the callback to finish

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

(defn watcher [source-paths callback]
  (let [;; setting up handling for watching individual files
        {:keys [files dirs]} (files-and-dirs source-paths)
        individual-file-map   (single-files files)
        canonical-source-dirs (set (map #(.getCanonicalPath %) dirs))
        ;; only watch dirs
        paths (map #(Paths/get (.toURI %)) (concat dirs (map io/file (keys individual-file-map))))
        path (first paths)
        fs   (.getFileSystem path)
        srvc (.newWatchService fs)
        quit (atom false)
        millis TimeUnit/MILLISECONDS]
    (letfn [(watch-all [root]
              (Files/walkFileTree root
                                  (reify
                                    FileVisitor
                                    (preVisitDirectory [_ dir _]
                                      (let [^Path dir dir]
                                        (. dir
                                           (register srvc
                                                     (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                                                  StandardWatchEventKinds/ENTRY_DELETE
                                                                  StandardWatchEventKinds/ENTRY_MODIFY])
                                                     (into-array [SensitivityWatchEventModifier/HIGH]))))
                                      FileVisitResult/CONTINUE)
                                    (postVisitDirectory [_ dir exc]
                                      FileVisitResult/CONTINUE)
                                    (visitFile [_ file attrs]
                                      FileVisitResult/CONTINUE)
                                    (visitFileFailed [_ file exc]
                                      FileVisitResult/CONTINUE))))
            (valid-file? [dir-path file-path]
              (or
               ;; just skip this if
               ;; there are no individual-files
               (empty? individual-file-map)
               ;; if file is on a path that is already being watched we are cool
               (some #(is-subdirectory % file-path) canonical-source-dirs)
               ;; if not we need to see if its an individually watched file
               (when-let [acceptable-paths (get individual-file-map (str dir-path))]
                 (some #(= (.getCanonicalPath %) file-path) acceptable-paths))))]
      (doseq [path paths]
        (watch-all path))

      ;; it may be better to share one watch service
      (add-watch quit :close-watch
                 (fn [_ _ _ v]
                   (when v
                     (Thread/sleep 500)
                     (.close srvc))))

      ;; I'm using go loop so it doesn't block.
      ;; too much complexity
      ;; TODO should probably use a future for sure      
      (go-loop [key nil]
        (when (and (or (nil? quit) (not @quit))
                   (or (nil? key) (. ^WatchKey key reset)))
          (let [key     (. srvc (poll 300 millis))]
            (when key
              (let [files (map :file-path
                               (filter
                                (fn [{:keys [dir-path file-path]}]
                                  (let [f (io/file file-path)
                                        n (.getName f)]
                                    (and (.exists f)
                                         (not (.isDirectory f))
                                         (not= \. (first n))
                                         (not= \# (first n))
                                         (valid-file? dir-path file-path))))
                                (mapv
                                 (fn [^WatchEvent e]
                                   {:dir-path (.watchable key)
                                    :file-path (str (.watchable key)
                                                    java.io.File/separator  (.. e context))})
                                 (seq (.pollEvents key)))))]
                (when-not (empty? files)
                  (callback files))))
            (recur key)))
        ))
    quit))
