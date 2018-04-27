(ns figwheel.main.schema
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.spec.alpha :as s]
   [expound.alpha :as exp]))

(defonce ^:dynamic *spec-meta* (atom {}))
(defn spec-doc [k doc] (swap! *spec-meta* assoc-in [k :doc] doc))

(defn file-exists? [s] (and s (.isFile (io/file s))))
(defn directory-exists? [s] (and s (.isDirectory (io/file s))))

(defn non-blank-string? [x] (and (string? x) (not (string/blank? x))))

(s/def ::edn (s/keys :opt-un
                     [::watch-dirs
                      ::reload-clj-files
                      ::ring-handler
                      ::ring-server
                      ::ring-server-options
                      ::ring-stack
                      ::ring-stack-options
                      ::mode
                      ::pprint-config
                      ::open-file-command
                      ::client-print-to
                      ]))

(s/def ::pprint-config boolean?)

(s/def ::mode #{:build-once :repl :serve})

(s/def ::watch-dirs (s/coll-of (s/and non-blank-string?
                                      directory-exists?)))

(s/def ::css-dirs (s/coll-of (s/and non-blank-string?
                                    directory-exists?)))

;; TODO make this verify that the handler exists
(s/def ::ring-handler (s/or :non-blank-string non-blank-string?
                            :symbol symbol?))

(s/def ::reload-clj-files (s/or :bool boolean?
                                :extension-coll (s/coll-of #{:clj :cljc})))

(s/def ::open-file-command non-blank-string?)

(s/def ::client-print-to (s/coll-of #{:console :repl}))

(s/def ::log-level #{:error :info :debug :trace :all :off})

(s/def ::log-file non-blank-string?)

(s/def ::log-syntax-error-style #{:verbose :concise})

(s/def ::ansi-color-output boolean?)

#_(exp/expound ::edn {:watch-dirs ["src"]
                      :ring-handler "asdfasdf/asdfasdf"
                      :reload-clj-files [:cljss :clj]})

#_(s/valid? ::edn {:watch-dirs ["src"]
                   :ring-handler "asdfasdf/asdfasdf"
                   :reload-clj-files [:cljss :clj]})

(defn validate-config! [config-data context-msg]
  (when-not (s/valid? ::edn config-data)
    (let [explained (exp/expound-str ::edn config-data)]
      (throw (ex-info (str context-msg "\n" explained)
                      {::error explained}))))
  true)
