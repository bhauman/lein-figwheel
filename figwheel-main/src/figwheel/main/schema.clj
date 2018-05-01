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
                     [::figwheel-core
                      ::hot-reload-cljs
                      ::load-warninged-code
                      ::watch-dirs
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
                      ::validate-config
                      ::rebel-readline
                      ]))

(s/def ::figwheel-core boolean?)
(spec-doc
 ::figwheel-core
 "Wether to include the figwheel.core library in the build. This
 enables hot reloading and client notification of compile time errors.
 Default: true

  :figwheel-core false")

(s/def ::hot-reload-cljs boolean?)
(spec-doc
 :figwheel.core/hot-reload-cljs
 "Whether or not figwheel.core should hot reload compiled
ClojureScript. Only has meaning when :figwheel is true.
Default: true

  :hot-reload-cljs false")

(s/def ::load-warninged-code boolean?)
(spec-doc
 ::load-warninged-code
 "If there are warnings in your code emitted from the compiler, figwheel
does not refresh. If you would like Figwheel to load code even if
there are warnings generated set this to true.
Default: false

  :load-warninged-code true")

(s/def ::validate-config boolean?)
(spec-doc
 ::validate-config
 "Whether to validate the figwheel-main.edn and build config (i.e.\".cljs.edn\") files.
Default: true

  :validate-config false")

(s/def ::rebel-readline boolean?)
(spec-doc
 ::rebel-readline
   "By default Figwheel engauges a Rebel readline editor when it starts
the ClojureScript Repl in the terminal that it is launched in.

This will only work if you have com.bhauman/rebel-readline-cljs in
your dependencies.

More about Rebel readline:
https://github.com/bhauman/rebel-readline

Default: true

  :readline false")

(s/def ::wait-time-ms integer?)
(spec-doc
 ::wait-time-ms
  "The number of milliseconds to wait before issuing reloads. Set this
higher to wait longer for changes. This is the interval from when the first
file change occurs until we finally issue a reload event.

Default: 50

  :wait-time-ms 50")

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

(s/def ::target-dir non-blank-string?)

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
