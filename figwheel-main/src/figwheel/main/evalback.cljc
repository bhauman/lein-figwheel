(ns figwheel.main.evalback
  (:require
   [clojure.string :as string]
   [figwheel.repl :as frepl]
   #?@(:cljs [[goog.log :as glog]]
       :clj [[figwheel.main.logging :as log]
             [clojure.java.io :as io]
             [cljs.tagged-literals :as tags]
             [clojure.tools.reader :as reader]
             [clojure.tools.reader.reader-types :as readers]
             [cljs.analyzer :as ana]
             [cljs.repl]
             [cljs.env]
             [clojure.java.shell :as shell]])))

#?(:cljs
   (do

     (defonce handlers (atom {}))

     (let [empty {}]
       (defn handle [uniq val]
         ((get @handlers uniq identity) val)
         (reset! handlers empty)))

     (defn ^:export eval-string [form-str callback]
       (let [uniq (str (gensym 'handler-))
             msg {:figwheel-event "eval-back"
                  :form-string
                  (str
                   "(figwheel.main.evalback/handle "
                   (pr-str uniq) " "
                   form-str ")")}]
         (swap! handlers assoc uniq callback)
         (figwheel.repl/respond-to-connection msg)))

     (defn eval-cljs [form callback]
       (eval-string (pr-str form) callback))

)

   :clj
   (do

(def timeout-val (Object.))

(defrecord EvalOnConnectionEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts])
  (-evaluate [this _ _  js]
    (when-let [conn (::connection this)]
      (let [prom (promise)
            _ (frepl/send-for-response* prom conn {:op :eval :code js})]
        {:status :success :value prom})))
  (-load [this provides url])
  (-tear-down [_]))

(defn read-cljs-string [form-str]
  (when-not (string/blank? form-str)
    (try
      {:form (binding [*ns* (create-ns ana/*cljs-ns*)
                       reader/resolve-symbol ana/resolve-symbol
                       reader/*data-readers* tags/*cljs-data-readers*
                       reader/*alias-map*
                       (apply merge
                              ((juxt :requires :require-macros)
                               (ana/get-namespace ana/*cljs-ns*)))]
                 (reader/read {:read-cond :allow :features #{:cljs}}
                              (readers/source-logging-push-back-reader
                               (java.io.StringReader. form-str))))}
      (catch Exception e
        {:exception (Throwable->map e)}))))

(defn eval-cljs [repl-env form]
  (cljs.repl/evaluate-form
   repl-env
   (assoc (ana/empty-env) :ns (ana/get-namespace ana/*cljs-ns*))
   "<cljs repl>"
   form
   (#'cljs.repl/wrap-fn form)))

(let [repl-env (EvalOnConnectionEnv.)]
  (defn eval-back-atcha [{:keys [session-id response] :as msg} cenv]
    (try
      (when-let [conn (get @figwheel.repl/*connections* session-id)]
        (binding [cljs.env/*compiler* cenv]
          (when-let [form (:form (read-cljs-string (:form-string response)))]
            (let [repl-env (assoc repl-env ::connection conn)]
              (eval-cljs repl-env form)))))
      (catch Throwable e
        (log/error "Error in eval back"  e)))))


(defn setup []
  (let [cenv cljs.env/*compiler*]
    (figwheel.repl/add-listener
     (fn [{:keys [response] :as msg}]
       (when (= "eval-back" (:figwheel-event response))
         (#'eval-back-atcha msg cenv))))))



))
