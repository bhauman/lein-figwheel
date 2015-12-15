(ns figwheel-sidecar.repl.driver
  (:require
    [clojure.core.async :refer [chan <!! <! >!! put! alts!! timeout close! go go-loop]]
    [cljs.repl :as cljs-repl]
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as reader-types]
    [figwheel-sidecar.repl.sniffer :as sniffer]
    [figwheel-sidecar.repl.messaging :as messaging]
    [cljs.analyzer :as ana]
    [figwheel-sidecar.repl.registry :as registry])
  (:import (clojure.lang IExceptionInfo)))

; -- driver construction ----------------------------------------------------------------------------------------------------

(defn get-initial-settings []
  {:commands-channel                     (chan)                                                                               ; channel for REPL commands (actually command records) from *in* and also network
   :last-ns                              (volatile! "")                                                                       ; string name of last namespace seen on prompt
   :current-job                          (volatile! nil)                                                                      ; command-record currently being processed {:request-id :kind :command}
   :active-repl-opts                     (volatile! nil)                                                                      ; cached repl opts
   :recording?                           (volatile! false)                                                                    ; should we record printing into *out* and *err*?
   :suppress-flushing                    (volatile! #{})                                                                      ; temporary suppression of flushing, contains a set of sniffer-keys
   :suppress-print-recording-until-flush (volatile! #{})})                                                                    ; temporary suppression of recording, contains a set of sniffer-keys)

(defn make-driver [extra-settings]
  (merge (get-initial-settings) extra-settings))

; -- getters/setters --------------------------------------------------------------------------------------------------------

(defn set-sniffer! [driver sniffer-key sniffer]
  (vreset! (get-in driver [:sniffers sniffer-key]) sniffer))

(defn get-sniffer [driver sniffer-key]
  (let [sniffer& (get-in driver [:sniffers sniffer-key])]
    (assert sniffer&)
    @sniffer&))

(defn get-last-ns [driver]
  @(:last-ns driver))

(defn set-last-ns [driver name]
  (vreset! (:last-ns driver) name))

; -- announcements ----------------------------------------------------------------------------------------------------------

(defn announce-job-start [driver job]
  (let [{:keys [request-id]} job]
    (messaging/announce-job-start (:server driver) request-id)))

(defn announce-job-end [driver job]
  (let [{:keys [request-id]} job]
    (messaging/announce-job-end (:server driver) request-id)))

(defn announce-ns [driver]
  (messaging/announce-repl-ns (:server driver) (get-last-ns driver)))

; -- job management ---------------------------------------------------------------------------------------------------------

(defn get-current-job [driver]
  @(:current-job driver))

(defn start-job! [driver job]
  {:pre [(not @(:current-job driver))]}
  (vreset! (:current-job driver) job)
  (announce-job-start driver job))

(defn stop-job! [driver]
  {:pre [@(:current-job driver)]}
  (announce-job-end driver (get-current-job driver))
  (vreset! (:current-job driver) nil))

; -- recording/flushing suppression -----------------------------------------------------------------------------------------

(defn suppress-recording-until-flush [driver sniffer-key]
  (let [suppress-var (:suppress-print-recording-until-flush driver)]
    (vreset! suppress-var (conj @suppress-var sniffer-key))))

(defn unsuppress-recording-until-flush [driver sniffer-key]
  (let [suppress-var (:suppress-print-recording-until-flush driver)]
    (vreset! suppress-var (disj @suppress-var sniffer-key))))

(defn suppressed-recording-until-flush? [driver sniffer-key]
  (let [suppress-var (:suppress-print-recording-until-flush driver)]
    (sniffer-key @suppress-var)))

(defn suppress-flushing [driver sniffer-key]
  (let [suppress-var (:suppress-flushing driver)]
    (vreset! suppress-var (conj @suppress-var sniffer-key))))

(defn unsuppress-flushing [driver sniffer-key]
  (let [suppress-var (:suppress-flushing driver)]
    (vreset! suppress-var (disj @suppress-var sniffer-key))))

(defn suppressed-flushing? [driver sniffer-key]
  (let [suppress-var (:suppress-flushing driver)]
    (sniffer-key @suppress-var)))

; -- print recording --------------------------------------------------------------------------------------------------------

(defn recording? [driver]
  @(:recording? driver))

(defn reset-sniffer-state! [driver sniffer-key]
  (unsuppress-recording-until-flush driver sniffer-key)
  (unsuppress-flushing driver sniffer-key)
  (sniffer/clear-content! (get-sniffer driver sniffer-key)))

(defn start-recording! [driver]
  (reset-sniffer-state! driver :stdout)
  (reset-sniffer-state! driver :stderr)
  (vreset! (:recording? driver) true))

(defn flush-sniffer!
  ([driver sniffer-key]
   (flush-sniffer! driver sniffer-key sniffer-key))
  ([driver sniffer-key output-kind]
   (let [sniffer (get-sniffer driver sniffer-key)
         content (sniffer/extract-content! sniffer)]
     (if-not (nil? content)
       (let [{:keys [request-id]} (get-current-job driver)]
         (messaging/report-output (:server driver) request-id output-kind content))))))

(defn stop-recording! [driver]
  (when (recording? driver)
    (flush-sniffer! driver :stdout)
    (flush-sniffer! driver :stderr)
    (vreset! (:recording? driver) false)))

; -- detection of active REPL options ---------------------------------------------------------------------------------------

(defn resolve-repl-opts []
  (if-let [repl-opts-var (resolve 'cljs.repl/*repl-opts*)]
    @repl-opts-var))

(defn resolve-active-repl-opts-if-needed! [driver]
  (if-not @(:active-repl-opts driver)
    (vreset! (:active-repl-opts driver) (resolve-repl-opts))))

(defn get-active-repl-opts! [driver]
  @(:active-repl-opts driver))

; -- eval externally --------------------------------------------------------------------------------------------------------

; cljs.repl/eval-cljs is private, this is a hack around it
(defn resolve-eval-cljs []
  (if-let [eval-cljs-var (resolve 'cljs.repl/eval-cljs)]
    @eval-cljs-var))

; here we mimic code in cljs.repl/repl*
(defn current-repl-special-fns [driver]
  (let [special-fns (:special-fns (get-active-repl-opts! driver))]
    (merge cljs-repl/default-special-fns special-fns)))

; here we mimic read-eval-print's behaviour in cljs.repl/repl*
(defn is-special-fn-call-form? [driver form]
  (let [is-special-fn? (set (keys (current-repl-special-fns driver)))]
    (and (seq? form) (is-special-fn? (first form)))))

; here we mimic parsing behaviour of cljs.repl/repl-read
(defn read-input [input]
  (let [rdr (reader-types/string-push-back-reader input)]
    (cljs-repl/skip-whitespace rdr)
    (reader/read {:read-cond :allow :features #{:cljs}} rdr)))

(defn is-special-fn-call? [driver input-text]
  (is-special-fn-call-form? driver (read-input input-text)))

(defn eval-external-command! [driver request-id code input]
  ; first, we echo user's input into stdout
  (println input)

  ; then we try to parse the code and put result on the channel
  ; in case the code is a special-fn call, we execute user's input
  ; otherwise we execute code provided from client (which may be a modified version of user's input)
  (let [effective-code (if (is-special-fn-call? driver input) input code)
        command (read-input effective-code)
        command-record {:kind       :external
                        :command    command
                        :request-id request-id}]
    (go
      (>!! (:commands-channel driver) command-record))))

; -- REPL handler factories -------------------------------------------------------------------------------------------------

(defn multiplexing-reader-factory
  "This factory creates a new REPL reading function. Normally this function is responsible for waiting for user input on
  stdin, parsing it and passing a valid form back. REPL system then calls it to parse next form, and so on.

  Our situation is more complicated. We need to provide two sources of inputs, one is traditional stdin as typed by the user.
  And second source is a sequence of commands incoming over socket from client-side. We want them to be treated as if they
  were typed into the REPL from standard input.

  We create a channel which will contain all commands. Network handler can put! there a command via exec-external-command!
  when a new message arives or the original cljs-repl/repl-read can put! there a command when user enters a new form from
  standard input. We just have to make sure we restart cljs-repl/repl-read if it is not already running."
  [driver]
  (let [pending-read? (volatile! false)]
    (fn [& args]
      (resolve-active-repl-opts-if-needed! driver)

      (stop-recording! driver)
      (if (get-current-job driver)
        (stop-job! driver))

      ; make sure we have a pending read in-flight
      (when-not @pending-read?
        (vreset! pending-read? true)
        (go
          (let [command (apply cljs-repl/repl-read args)
                command-record {:kind    :stdin
                                :command command}]
            (>!! (:commands-channel driver) command-record)
            (vreset! pending-read? false))))

      ; wait & serve next input from the channel (can be produced by cljs-repl/repl-read or exec-external-command!)
      (let [command-record (<!! (:commands-channel driver))]
        (case (:kind command-record)
          :stdin (:command command-record)
          :external (do
                      (start-job! driver command-record)
                      (start-recording! driver)
                      (:command command-record)))))))

(defn custom-eval-factory [driver]
  (fn [& args]
    (when-let [eval-cljs (resolve-eval-cljs)]
      (let [result (apply eval-cljs args)]
        ; main REPL loop calls eval and then immediatelly prints result value
        ; we want to prevent that printing to be recorded
        (if (recording? driver)
          (suppress-recording-until-flush driver :stdout))
        result))))

(defn custom-prompt-factory [driver]
  (fn []
    (let [stdout-sniffer (get-sniffer driver :stdout)]
      (set-last-ns driver (str ana/*cljs-ns*))
      (announce-ns driver)
      (suppress-flushing driver :stdout)
      (cljs-repl/repl-prompt)
      (unsuppress-flushing driver :stdout)
      (sniffer/clear-content! stdout-sniffer))))

(defn custom-caught-factory [driver]
  (fn [e repl-env opts]
    (let [orig-call #(cljs-repl/repl-caught e repl-env opts)]
      (if-not (recording? driver)
        (orig-call)
        (if (and (instance? IExceptionInfo e)
                 (#{:js-eval-error :js-eval-exception} (:type (ex-data e))))
          (do
            ; we want to prevent recording javascript errors and exceptions,
            ; because those were already reported on client-side directly
            ; other exceptional cases should be recorded as usual (for example exceptions originated in compiler)
            (stop-recording! driver)
            (orig-call)
            (start-recording! driver))
          (do
            ; we've got a java exception with a possibly long stack trace
            ; it will be printed in cljs.repl/repl-caught via (.printStackTrace e *err*)
            ; we capture output and send it to client side with special kind :java-trace
            ; with this hint, client-side should implement a nice way how to present this to the user
            (suppress-flushing driver :stderr)
            (orig-call)
            (unsuppress-flushing driver :stderr)
            (flush-sniffer! driver :stderr :java-trace)))))))

; -- sniffer handlers -------------------------------------------------------------------------------------------------------

(defn flush-handler
  "This method gets callled every time *out* (or *err*) gets flushed.
  If flushing is allowed, our job is to send accumulated (recorded) output to client side.
  In case of recording was suppressed, we throw away the conente and just flip the flag back instead."
  [driver sniffer-key]
  (if-not (suppressed-flushing? driver sniffer-key)
    (let [sniffer (get-sniffer driver sniffer-key)]
      (if-let [content (sniffer/extract-all-lines-but-last! sniffer)]
        (if (recording? driver)
          (let [{:keys [request-id]} (get-current-job driver)]
            (if (suppressed-recording-until-flush? driver sniffer-key)
              (unsuppress-recording-until-flush driver sniffer-key)
              (messaging/report-output (:server driver) request-id sniffer-key content))))))))


; -- initialization ---------------------------------------------------------------------------------------------------------

(defn start-repl-with-driver [build figwheel-server opts start-fn]
  (let [driver (make-driver {:build    build
                             :server   figwheel-server
                             :sniffers {:stdout (volatile! nil)
                                        :stderr (volatile! nil)}})
        repl-opts (assoc opts
                    :read (multiplexing-reader-factory driver)
                    :eval (custom-eval-factory driver)
                    :prompt (custom-prompt-factory driver)
                    :caught (custom-caught-factory driver)
                    :bind-err false)]
    (let [stdout-sniffer (sniffer/make-sniffer *out* (partial flush-handler driver :stdout))
          stderr-sniffer (sniffer/make-sniffer *out* (partial flush-handler driver :stderr))]                                 ; *out* is here on purpose, see :bind-err and its effect when true (default)
      (try
        (set-sniffer! driver :stdout stdout-sniffer)
        (set-sniffer! driver :stderr stderr-sniffer)
        (registry/register-driver! :current-repl driver)
        (binding [*out* stdout-sniffer
                  *err* stderr-sniffer]
          (start-fn repl-opts))
        (finally
          (sniffer/destroy-sniffer stdout-sniffer)
          (sniffer/destroy-sniffer stderr-sniffer))))))