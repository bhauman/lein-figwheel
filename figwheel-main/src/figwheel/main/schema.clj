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
                      ::validate-config
                      ::rebel-readline
                      ::watch-time-ms
                      ::pprint-config
                      ::mode
                      ::watch-dirs
                      ::css-dirs
                      ::ring-handler
                      ::ring-server
                      ::ring-server-options
                      ::ring-stack
                      ::ring-stack-options

                      ::reload-clj-files
                      ::open-file-command
                      ::client-print-to
                      ::log-level
                      ::log-file
                      ::log-syntax-error-style
                      ::ansi-color-output
                      ::target-dir

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
(spec-doc
 ::pprint-config
 "When :pprint-config is set to true. The figwheel.main will print the
computed config information and will terminate the process. Useful for
understanding what figwheel.main adds to your configuration before it
compiles your build.
Default: false

:pprint-config true")

(s/def ::mode #{:build-once :repl :serve})
(spec-doc
 ::mode
 "The mode indicates the behavior that occurs after a compile.

:repl indicates that repl sill be started
:serve indicates that a server will be started
:build-once indicates that a compile will not be follwed by any action

This is mainly intended for use when you are launching figwheel.main from a script.

Normally defaults to :repl")


(s/def ::watch-dirs (s/coll-of (s/and non-blank-string?
                                      directory-exists?)))
(spec-doc
 ::watch-dirs
 "A list of ClojureScript source directories to be watched and compiled on change.

:watch-dirs [\"cljs-src\"]")


(s/def ::css-dirs (s/coll-of (s/and non-blank-string?
                                    directory-exists?)))
(spec-doc
 ::css-dirs
 "A list of CSS source directories to be watched and reloaded into the browser.

:css-dirs [\"resource/public/css\"]")

;; TODO make this verify that the handler exists?
(s/def ::ring-handler (s/or :non-blank-string non-blank-string?
                            :symbol symbol?))
(spec-doc
 ::ring-handler
 "A symbol or string indicating a ring-handler to embed in the
figwheel.repl server. This aids in quickly getting a dev server up and
running. If the devserver doesn't meet your needs you can simply start
your own server, the figwheel.client will still be able to connect to its
websocket endpoint. Default: none")

(s/def ::ring-server-options (s/keys :opt-un [::port]))
(spec-doc
 ::ring-server-options
 "All the options to forward to the ring-jetty-adapter/run-jetty function
which figwheel.main uses to run its ring server.

All the available options are documented here:
https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/src/ring/adapter/jetty.clj#L127

This will normally be used to set the :port and :host of the server.

Most uses of these options are considered advanced if you find
yourself using many of these options you problably need to run your
own server outside of figwheel.main.")

(s/def ::ring-stack (s/or :non-blank-string non-blank-string?
                          :symbol symbol?))

(spec-doc
 ::ring-stack
 "The fighweel.repl server has a notion of
a :ring-stack. The :ring-stack is a set of base ring-middleware to
wrap around a supplied :ring-handler.

The default :ring-stack is a slightly modified
ring.middleware.defaults/wrap-defaults")

(s/def ::ring-stack-options map?)
(spec-doc
 ::ring-stack-options
 (str "The fighweel.repl server has a notion of
a :ring-stack. The :ring-stack is a set of base ring-middleware to
wrap around a supplied :ring-handler.

The default :ring-stack is a slightly modified
ring.middleware.defaults/wrap-defaults.

:ring-stack-options are the options that figwheel.repl supplies to
ring.middleware.defaults/wrap-defaults.

The default options are slightly modified from ring.middleware.defaults/site-defaults:

" (when-let [opt (resolve 'figwheel.server.ring/default-options)]
    (with-out-str (clojure.pprint/pprint (deref opt))))
"
You can override these options by suppling your own to :ring-stack-options

If these options are changed significantly don't be suprised if the
figwheel stops behaving correctly :)"))

(s/def ::reload-clj-files (s/or :bool boolean?
                                :extension-coll (s/coll-of #{:clj :cljc})))
(spec-doc
 ::reload-clj-files
 "Figwheel naively reloads clj and cljc files on the :source-paths.
It doesn't reload clj dependent files like tools.namspace.

Figwheel does note if there is a macro in the changed clj or cljc file
and then marks any cljs namespaces that depend on the clj file for
recompilation and then notifies the figwheel client that these
namespaces have changed.

If you want to disable this behavior:

  :reload-clj-files false

Or you can specify which suffixes will cause the reloading

  :reload-clj-files #{:clj :cljc}")

(s/def ::open-file-command non-blank-string?)
(spec-doc
 ::open-file-command
   "A path to an executable shell script that will be passed a file and
line information for a particular compilation error or warning.

A script like this would work
ie. in  ~/bin/myfile-opener
#! /bin/sh
emacsclient -n +$2:$3 $1

The add this script in your config:

  :open-file-command \"myfile-opener\"

But thats not the best example because Figwheel handles 'emacsclient'
as a special case so as long as 'emacsclient' is on the shell path you can
simply do:

  :open-file-command \"emacsclient\"

and Figwheel will call emacsclient with the correct args.")

(s/def ::client-print-to (s/coll-of #{:console :repl}))
(spec-doc
 ::client-print-to
 "The figwheel.repl client can direct printed (via pr) output to the
repl and or the console. :client-print-to is a list of where you
want print output directed. The output choices are :console and :repl
Default: [:console :repl]

  :client-print-to [:console]")

(s/def ::log-level #{:error :info :debug :trace :all :off})
(spec-doc
 ::log-level
 "The level to set figwheel.main java.util.logger to.
Can be one of: :error :info :debug :trace :all :off

  :log-level :error")

(s/def ::log-file non-blank-string?)
(spec-doc
 ::log-file
 "The name of a file to redirect the figwheel.main logging to. This
will only take effect when a REPL has been started.

  :log-file \"figwheel-main.log\"")

(s/def ::log-syntax-error-style #{:verbose :concise})
(spec-doc
 ::log-syntax-error-style
 "Figwheel.main logging prints out compile time syntax errors which
includes displaying the erroneous code.
Setting :log-syntax-error-style to :concise will cause the logging to
not display the erroneous code.
Available options: :verbose, :concise
Default: :verbose

  :log-syntax-error-style :concise" )

(s/def ::ansi-color-output boolean?)
(spec-doc
 ::ansi-color-output
   "Figwheel makes an effort to provide colorful text output. If you need
to prevent ANSI color codes in figwheel output set :ansi-color-output
to false.  Default: true

  :ansi-color-output false")

(s/def ::target-dir non-blank-string?)
(spec-doc
 ::target-dir
 "A String that specifies the target directory component of the path
where figwheel.main outputs compiled ClojureScript

The default :output-dir is composed of:
[[:target-dir]]/public/cljs-out/[[build-id]]

The default :output-to is composed of:
[[:target-dir]]/public/cljs-out/[[build-id]]-main.js

If you are using the default figwheel.repl server to serve compiled
assets, it is very important that the :target-dir be on the classpath.

The default value of :target-dir is \"target\"

  :target-dir \"cljs-target\"")

(s/def ::connect-url non-blank-string?)

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
