(ns figwheel-sidecar.build-middleware.injection
  (:require
   [figwheel-sidecar.config :as config]
   [clojure.java.io :as io]
   [cljs.env :as env]
   [cljs.analyzer.api :as ana-api]))

#_(remove-ns 'figwheel-sidecar.build-middleware.injection)

(defn- node? [build]
  (when-let [target (get-in build [:build-options :target])]
    (= target :nodejs)))

(defn- bundle? [build]
  (when-let [target (get-in build [:build-options :target])]
    (= target :bundle)))

(defn- webworker? [build]
  (when-let [target (get-in build [:build-options :target])]
    (= target :webworker)))

(defn- has-main? [build]
  (get-in build [:build-options :main]))

(defn- has-modules? [build]
  (get-in build [:build-options :modules]))

(defn- has-output-to? [build]
  (get-in build [:build-options :output-to]))

(defn add-connect-script! [figwheel-server build]
  ;; cannot just supply arbitrary connection code to the compiler because of the following issues
  ;; CLJS bugs
  ;; - another where the connection script isn't generated in a node env
  ;; https://github.com/bhauman/lein-figwheel/issues/474
  ;; - one where analysis cache is invlidated
  ;; https://github.com/bhauman/lein-figwheel/issues/489
  (if (config/figwheel-build? build)
    (let [build (config/update-figwheel-connect-options figwheel-server build)
          devcards? (get-in build [:figwheel :devcards])]
      (-> build
          ;; TODO this only needs to be done in certain cases
          ;; TODO eventually we will be loading devcards with 'devcards.preload
          (update-in [:build-options :preloads] (fn [x] (if devcards?
                                                          ((fnil conj []) x 'devcards.core)
                                                          x)))
          ;; only in cases where there is a not an main for now
          (update-in [:build-options :preloads]
                     (fnil conj [])
                     (if (and (has-main? build)
                              (not (bundle? build))
                              (not (has-modules? build))
                              (not (node? build))
                              (not (webworker? build)))
                       'figwheel.connect
                       'figwheel.preload))
          (update-in [:build-options :external-config :figwheel/config] #(if % % (get build :figwheel {})))
          ;; might want to add in devcards jar path here :)
          (update-in [:compile-paths]
                     (fn [res] (vec (if-let [devcards-src (and devcards?
                                                               (cljs.env/with-compiler-env (:compiler-env build)
                                                                 (not (ana-api/find-ns 'devcards.core)))
                                                               (io/resource "devcards/core.cljs"))]
                                      (cons devcards-src res)
                                      res))))
          ;; this needs to be in the (:options (:compiler-env build))
          #_(update-in [:build-options] (fn [bo] (if devcards?
                                                   (assoc bo :devcards true)
                                                   bo)))))
    build))

;; continuing to append require for the html document case to enable
;; document.onload callbacks to work
(defn esc-fmt [a & args]
  (apply format a (map pr-str args)))

(defn document-write-src-script [src]
  (esc-fmt "\ndocument.write(%s);"
           (format "<script>%s</script>" src)))

(defn append-src-script [build src-code]
  (let [output-to (has-output-to? build)
        line (if (and (has-main? build) (not (node? build)) (not (webworker? build)))
               (str (document-write-src-script src-code))
               (format "\n%s" src-code))]
    (when (and output-to (.exists (io/file output-to)))
      (spit output-to line :append true))))

(defn append-connection-init! [build]
  (when (and (config/figwheel-build? build)
             (has-main? build)
             (not (bundle? build))
             (not (has-modules? build))
             (not (node? build))
             (not (webworker? build)))
    (append-src-script build "figwheel.connect.start();")))

(defn hook [build-fn]
  (fn [{:keys [figwheel-server build-config] :as build-state}]
    (build-fn
     (assoc build-state
            :build-config (add-connect-script! figwheel-server build-config)))
    ;; this will probably not be needed sometime in the future but for
    ;; now it is safer to let goog.base to do all the loading in an HTML
    ;; environment before figwheel gets loaded
    (append-connection-init! build-config)))
