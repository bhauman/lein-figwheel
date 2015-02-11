(ns figwheel-sidecar.repl
  (:require
   [clojure.pprint :as p]
   [cljs.repl]
   [cljs.util]
   [cljs.env :as env]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [figwheel-sidecar.core :as fig]))

;; chrome error
;;  at error_test2 (http://localhost:3449/js/out/figwheel/client.js?zx=c852wj4xz1qe:384:8)
;; node error
;;  at error_test2 (/Users/brucehauman/workspace/noderer/out/noderer/core.js:16:8)
;; safari 
;;  error_test2@http://localhost:3449/js/out/figwheel/client.js:384:11
;; firefox is the same
;;  error_test2@http://localhost:3449/js/out/figwheel/client.js:384:1

;; canonical error form
;; error_test2@http://localhost:3449/js/out/figwheel/client.js:384:11

(defn at-start-line->canonical-stack-line [line]
  (let [[_ function file-part] (re-matches #"\s*at\s*(\S*)\s*\((.*)\)" line)]
    (str function "@" file-part)))

(defn to-canonical-stack-line [line]
  (if (re-matches #"\s*at\s*.*" line)
    (at-start-line->canonical-stack-line line)
    line))

(defn output-dir-relative-file [base-path file]
  (let [short (string/replace-first file base-path "")]
    (first (string/split short #"\?"))))

(defn stack-line->stack-line-map
  [base-path stack-line]
  (let [stack-line (to-canonical-stack-line stack-line)
        [function file line column]
        (rest (re-matches #"(.*)@(.*):([0-9]+):([0-9]+)"
                stack-line))]
    (when (and file function line column)
      { :file      (output-dir-relative-file base-path file)
        :function  function
        :line      (Long/parseLong line)
        :column    (Long/parseLong column) })))

(defn stack-line? [l]
  (and
   (map? l)
   (string?  (:file l))
   (string?  (:function l))
   (integer? (:line l))
   (integer? (:column l))))

(defn handle-stack-trace [base-path stk-str]
  (let [stk-tr (string/split-lines stk-str)
        grouped-lines (group-by stack-line? (mapv (partial stack-line->stack-line-map base-path)
                                                  stk-tr))]
    (if (< (count (grouped-lines true))
           (count (grouped-lines nil)))
      (string/join "\n" stk-tr)
      (vec (grouped-lines true)))))

(defn eval-js [{:keys [browser-callbacks output-dir] :as figwheel-server} js]
  (let [callback-name (str (gensym "repl_eval_"))
        out (chan)
        callback (fn [result]
                   (swap! browser-callbacks dissoc callback-name)
                   (put! out result)
                   (go
                     (<! (timeout 2000))
                     (close! out)))]
    (swap! browser-callbacks assoc callback-name callback)
    (fig/send-message! figwheel-server :repl-eval {:code js :callback-name callback-name})
    (let [[v ch] (alts!! [out (timeout 8000)])]
      (if (= ch out)
        v
        {:status :exception
         :value "Eval timed out!"
         :stacktrace "No stacktrace available."}))))

(defn connection-available?
  [connection-count build-id]
  (not
   (zero?
    (+ (or (get @connection-count build-id) 0)
       (or (get @connection-count nil) 0)))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [connection-count build-id]}]
  (when-not (connection-available? connection-count build-id)
    (loop []
      (when-not (connection-available? connection-count build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks]}]
  (swap! browser-callbacks assoc "figwheel-repl-print"
         (fn [args] (apply println args))))

(defrecord FigwheelEnv [figwheel-server]
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server js))
      ;; this is not used for figwheel
  (-load [this ns url] true)
  (-tear-down [_] true)
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [repl-env stacktrace error build-options]
    (handle-stack-trace (:base-path error) (:stacktrace error))))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               (select-keys build-options [:output-dir :output-to])))
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (cljs.repl/repl* (repl-env figwheel-server build)
                    (merge (assoc (or (:compiler build) (:build-options build))
                                  :warn-on-undeclared true)
                           opts))))

