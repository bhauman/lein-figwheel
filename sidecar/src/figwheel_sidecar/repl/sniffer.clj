(ns figwheel-sidecar.repl.sniffer
  (:require [clojure.string :as string])
  (:import (java.io StringWriter PrintWriter)))

(def sniffer->proxy (atom {}))

(defmacro enter-gate [gate-keeper & body]
  `(try
     (vreset! ~gate-keeper (inc (deref ~gate-keeper)))
     ~@body
     (finally
       (vreset! ~gate-keeper (dec (deref ~gate-keeper))))))

(defn open? [gate]
  (zero? @gate))

; -- constructor ------------------------------------------------------------------------------------------------------------

; see http://docs.oracle.com/javase/7/docs/api/java/io/Writer.html
(defn make-writer-proxy [writer flush-handler]
  (let [gate-keeper (volatile! 0)]
    (proxy [StringWriter] []
      (write
        ([x]
         (if (open? gate-keeper)
           (.write writer x))
         (enter-gate gate-keeper
           (proxy-super write x)))
        ([x off len]
         (if (open? gate-keeper)
           (.write writer x off len))
         (enter-gate gate-keeper
           (proxy-super write x off len))))
      (append
        ([x]
         (if (open? gate-keeper)
           (.append writer x))
         (enter-gate gate-keeper
           (proxy-super append x))
         this)
        ([x start end]
         (if (open? gate-keeper)
           (.append writer x start end))
         (enter-gate gate-keeper
           (proxy-super append x start end))
         this))
      (close
        ([]
         (.close writer)
         (proxy-super close)))
      (flush
        ([]
         (.flush writer)
         (proxy-super flush)
         (flush-handler))))))

; note: some cljs.repl code expects *err* to be PrintWriter instance, not just a Writer instance
;       e.g. it calls (.printStackTrace e *err*)
(defn make-sniffer [writer flush-handler]
  (let [proxy (make-writer-proxy writer flush-handler)
        sniffer (PrintWriter. proxy true)]
    (swap! sniffer->proxy assoc sniffer proxy)
    sniffer))

(defn destroy-sniffer [sniffer]
  {:pre  [(instance? PrintWriter sniffer)]}
  (swap! sniffer->proxy dissoc sniffer))

; -- helpers ----------------------------------------------------------------------------------------------------------------

(defn get-writer [sniffer]
  {:pre  [(instance? PrintWriter sniffer)]
   :post [(instance? StringWriter %)]}
  (get @sniffer->proxy sniffer))

(defn clear-content! [sniffer]
  {:pre  [(instance? PrintWriter sniffer)]}
  (let [buffer (.getBuffer (get-writer sniffer))]
    (.setLength buffer 0)))

(defn extract-all-lines-but-last! [sniffer]
  {:pre  [(instance? PrintWriter sniffer)]}
  (let [content (.toString (get-writer sniffer))]
    (if-not (empty? content)
      (let [lines (string/split content #"\n" -1)                                                                             ; http://stackoverflow.com/a/29614863/84283
            lines-ready (butlast lines)
            remainder (last lines)]
        (clear-content! sniffer)
        (.append sniffer remainder)
        (string/join "\n" lines-ready)))))

(defn extract-content! [sniffer]
  {:pre  [(instance? PrintWriter sniffer)]}
  (let [content (.toString (get-writer sniffer))]
    (when-not (empty? content)
      (clear-content! sniffer)
      content)))