(ns figwheel.main.schema
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.spec.alpha :as s]
   [expound.alpha :as exp]))

(def ^:dynamic *spec-meta* (atom {}))
(defn def-spec-meta [k & args]
  (assert (even? (count args)))
  (swap! *spec-meta* assoc k (assoc (into {} (map vec (partition 2 args)))
                                    :position (count (keys @*spec-meta*))
                                    :key k)))

(defn spec-doc [k doc] (swap! *spec-meta* assoc-in [k :doc] doc))

(defn file-exists? [s] (and s (.isFile (io/file s))))
(defn directory-exists? [s] (and s (.isDirectory (io/file s))))

(defn non-blank-string? [x] (and (string? x) (not (string/blank? x))))

(s/def ::edn (s/keys :opt-un
                     [::watch-dirs
                      ::css-dirs
                      ::ring-handler
                      ::ring-server-options
                      ::rebel-readline
                      ::pprint-config
                      ::open-file-command
                      ::figwheel-core
                      ::hot-reload-cljs
                      ::connect-url
                      ::reload-clj-files
                      ::log-file
                      ::log-level
                      ::log-syntax-error-style
                      ::load-warninged-code
                      ::ansi-color-output
                      ::validate-config
                      ::target-dir

                      ::client-print-to
                      ::ring-stack
                      ::ring-stack-options
                      ::watch-time-ms
                      ::mode
                      ::ring-server]))

(s/def ::watch-dirs (s/coll-of (s/and non-blank-string?
                                      directory-exists?)))
(def-spec-meta ::watch-dirs
  :doc
  "A list of ClojureScript source directories to be watched and compiled on change.

:watch-dirs [\"cljs-src\"]"
  :group :common)

(s/def ::css-dirs (s/coll-of (s/and non-blank-string?
                                    directory-exists?)))
(def-spec-meta ::css-dirs
  :doc
  "A list of CSS source directories to be watched and reloaded into the browser.

    :css-dirs [\"resource/public/css\"]"
  :group :common)

(s/def ::ring-handler (s/or :non-blank-string non-blank-string?
                            :symbol symbol?))
(def-spec-meta ::ring-handler
  :doc
  "A symbol or string indicating a ring-handler to embed in the
figwheel.repl server. This aids in quickly getting a dev server up and
running. If the figwheel server doesn't meet your needs you can simply
start your own server, the figwheel.client will still be able to
connect to its websocket endpoint.
Default: none

    :ring-handler my-project.server/handler"
  :group :common)

(s/def ::ring-server-options (s/keys :opt-un [::port]))
(def-spec-meta ::ring-server-options
  :doc
 "All the options to forward to the `ring-jetty-adapter/run-jetty` function
which figwheel.main uses to run its ring server.

All the available options are documented here:
https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/src/ring/adapter/jetty.clj#L127

This will normally be used to set the `:port` and `:host` of the server.

Most uses of these options are considered advanced if you find
yourself using many of these options you problably need to run your
own server outside of figwheel.main."
  :group :common)

(s/def ::rebel-readline boolean?)
(def-spec-meta ::rebel-readline
  :doc
  "By default Figwheel engauges a Rebel readline editor when it starts
the ClojureScript Repl in the terminal that it is launched in.

This will only work if you have `com.bhauman/rebel-readline-cljs` in
your dependencies.

More about Rebel readline:
https://github.com/bhauman/rebel-readline

Default: true

  :rebel-readline false"
  :group :common)

(s/def ::pprint-config boolean?)
(def-spec-meta ::pprint-config
  :doc
  "When `:pprint-config` is set to true. The `figwheel.main` will print the
computed config information and will terminate the process. Useful for
understanding what figwheel.main adds to your configuration before it
compiles your build.

Default: false

  :pprint-config true"
  :group :common)

(s/def ::open-file-command non-blank-string?)
(def-spec-meta ::open-file-command
  :doc
  "A path to an executable shell script that will be passed a file and
line information for a particular compilation error or warning.

A script like this would work
ie. in  ~/bin/myfile-opener

    #! /bin/sh
    emacsclient -n +$2:$3 $1

The add this script in your config:

    :open-file-command \"myfile-opener\"

But thats not the best example because Figwheel handles `emacsclient`
as a special case so as long as `emacsclient` is on the shell path you can
simply do:

    :open-file-command \"emacsclient\"

and Figwheel will call emacsclient with the correct args."
  :group :common)

(s/def ::figwheel-core boolean?)
(def-spec-meta ::figwheel-core
  :doc
 "Wether to include the figwheel.core library in the build. This
 enables hot reloading and client notification of compile time errors.
 Default: true

  :figwheel-core false"
  :group :common)

(s/def ::hot-reload-cljs boolean?)
(def-spec-meta :figwheel.core/hot-reload-cljs
  :doc
 "Whether or not figwheel.core should hot reload compiled
ClojureScript. Only has meaning when :figwheel is true.
Default: true

  :hot-reload-cljs false"
  :group :common)

(s/def ::connect-url non-blank-string?)
(def-spec-meta ::connect-url
  :doc
 "The url that the figwheel repl client will use to connect back to
the server.

This url is actually a template that will be filled in.  For example
the default `:connect-url` is:

    \"ws://[[config-hostname]]:[[server-port]]/figwheel-connect\"

The available template variables are:

For the server side:

    [[config-hostname]]  the host supplied in :ring-server-options > :host or \"localhost\"
    [[server-hostname]]  the java.InetAddress localhost name - \"Bruces-MacBook-Pro.local\" on my machine
    [[server-ip]]        the java.InetAddress localhost ip interface - normally 192.168.x.x
    [[server-port]]      the host supplied in :ring-server-options > :host or \"localhost\" or the default port

On the client side:
    [[client-hostname]]  the js/location.hostname on the client
    [[client-port]]      the js/location.port on the client

If the url starts with a Websocket scheme \"ws://\" a websocket
connection will be established. If the url starts with an http scheme
\"http\" an http long polling connection will be established."
  :group :common)

(s/def ::reload-clj-files (s/or :bool boolean?
                                :extension-coll (s/coll-of #{:clj :cljc})))
(def-spec-meta ::reload-clj-files
  :doc
 "Figwheel naively reloads `clj` and `cljc` files on the `:source-paths`.
It doesn't reload clj dependent files like tools.namspace.

Figwheel does note if there is a macro in the changed `clj` or `cljc` file
and then marks any cljs namespaces that depend on the `clj` file for
recompilation and then notifies the figwheel client that these
namespaces have changed.

If you want to disable this behavior:

    :reload-clj-files false

Or you can specify which suffixes will cause the reloading

  :reload-clj-files #{:clj :cljc}"
  :group :common)

(s/def ::log-file non-blank-string?)
(def-spec-meta ::log-file
  :doc
 "The name of a file to redirect the figwheel.main logging to. This
will only take effect when a REPL has been started.

    :log-file \"figwheel-main.log\""
  :group :common)

(s/def ::log-level #{:error :info :debug :trace :all :off})
(def-spec-meta ::log-level
  :doc
 "The level to set figwheel.main java.util.logger to.
Can be one of: `:error` `:info` `:debug` `:trace` `:all` `:off`

    :log-level :error"
  :group :common)

(s/def ::log-syntax-error-style #{:verbose :concise})
(def-spec-meta ::log-syntax-error-style
  :doc
 "figwheel.main logging prints out compile time syntax errors which
includes displaying the erroneous code.
Setting `:log-syntax-error-style` to `:concise` will cause the logging to
not display the erroneous code.
Available options: `:verbose`, `:concise`
Default: `:verbose`

    :log-syntax-error-style :concise"
  :group :common)

(s/def ::load-warninged-code boolean?)
(def-spec-meta ::load-warninged-code
  :doc
 "If there are warnings in your code emitted from the compiler, figwheel
does not refresh. If you would like Figwheel to load code even if
there are warnings generated set this to true.
Default: false

  :load-warninged-code true"
  :group :common)

(s/def ::ansi-color-output boolean?)
(def-spec-meta ::ansi-color-output
  :doc
   "Figwheel makes an effort to provide colorful text output. If you need
to prevent ANSI color codes in figwheel output set `:ansi-color-output`
to false.  Default: true

    :ansi-color-output false"
  :group :common)

(s/def ::validate-config boolean?)
(def-spec-meta ::validate-config
  :doc
 "Whether to validate the figwheel-main.edn and build config (i.e.\".cljs.edn\") files.
Default: true

  :validate-config false"
  :group :common)

(s/def ::target-dir non-blank-string?)
(def-spec-meta ::target-dir
  :doc
 "A String that specifies the target directory component of the path
where figwheel.main outputs compiled ClojureScript

The default `:output-dir` is composed of:

    [[:target-dir]]/public/cljs-out/[[build-id]]

The default `:output-to` is composed of:

    [[:target-dir]]/public/cljs-out/[[build-id]]-main.js

If you are using the default figwheel.repl server to serve compiled
assets, it is very important that the :target-dir be on the classpath.

The default value of `:target-dir` is \"target\"

    :target-dir \"cljs-target\""
  :group :common)

;; -------------------------------XXXXXXXXXXXX

(s/def ::client-print-to (s/coll-of #{:console :repl}))
(def-spec-meta ::client-print-to
  :doc
 "The `figwheel.repl` client can direct printed (via pr) output to the
repl and or the console. `:client-print-to` is a list of where you
want print output directed. The output choices are `:console` and `:repl`
Default: [:console :repl]

    :client-print-to [:console]"
  :group :un-common)

(s/def ::ring-stack (s/or :non-blank-string non-blank-string?
                          :symbol symbol?))

(def-spec-meta ::ring-stack
  :doc
 "The fighweel server has a notion of a `:ring-stack`. The
`:ring-stack` is a composition of basic ring-middleware (think
sessions) to wrap around a supplied `:ring-handler`.

The default `:ring-stack` is a slightly modified
`ring.middleware.defaults/wrap-defaults`"
  :group :un-common)

(s/def ::ring-stack-options map?)
(def-spec-meta ::ring-stack-options
  :doc
 (str "The fighweel.repl server has a notion of a `:ring-stack`. The
`:ring-stack` is a composition of basic ring-middleware to wrap around
a supplied `:ring-handler`.

The default `:ring-stack` is a slightly modified
ring.middleware.defaults/wrap-defaults.

`:ring-stack-options` are the options that figwheel.repl supplies to
`ring.middleware.defaults/wrap-defaults`.

The default options are slightly modified from `ring.middleware.defaults/site-defaults`:

```
" (when-let [opt (resolve 'figwheel.server.ring/default-options)]
    (with-out-str (clojure.pprint/pprint (deref opt))))
"```

You can override these options by suppling your own to `:ring-stack-options`

If these options are changed significantly don't be suprised if the
figwheel stops behaving correctly :)")
  :group :un-common)

(s/def ::wait-time-ms integer?)
(def-spec-meta ::wait-time-ms
  :doc
  "The number of milliseconds to wait before issuing reloads. Set this
higher to wait longer for changes. This is the interval from when the first
file change occurs until we finally issue a reload event.

Default: 50

  :wait-time-ms 50"
  :group :un-common)

(s/def ::mode #{:build-once :repl :serve})
(def-spec-meta ::mode
  :doc
 "The `:mode` indicates the behavior that occurs after a compile.
Options: `:repl` `:serve` or `:build-once`

`:repl` indicates that repl sill be started
`:serve` indicates that a server will be started
`:build-once` indicates that a compile will not be follwed by any action

This is mainly intended for use when you are launching figwheel.main from a script.

Normally defaults to `:repl`"
  :group :un-common)



;; ------------------------------------------------------------
;; Validate
;; ------------------------------------------------------------

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

;; ------------------------------------------------------------
;; Generate docs
;; ------------------------------------------------------------

(defn last-line-example? [doc]
  (try (when-let [lns (not-empty (string/split-lines doc))]
         (when (> (count lns) 1)
           (keyword? (read-string (last (string/split-lines doc))))))
       (catch Throwable t nil)))

(defn split-out-example [doc]
  (if (last-line-example? doc)
    (let [lns (string/split-lines doc)]
      #_(prn lns)
      [(string/join "\n" (butlast lns)) (last lns)])
    [doc]))

(defn markdown-option-docs [key-datas]
  (string/join
   "\n\n"
   (mapv (fn [{:keys [key doc]}]
           (let [k (keyword (name key))
                 [doc' example] (split-out-example doc)]
             (cond-> (format "## %s\n\n%s" (pr-str k) doc')
               example (str "\n```\n" (string/trim example) "\n```"))))
         key-datas)))

(defn markdown-docs []
  (let [{:keys [common un-common]}  (group-by :group (sort-by :position (vals @*spec-meta*)))]
    (str "# Figwheel Main Configuration Options\n\n"
         "The following options can be supplied to `figwheel.main` via the `figwheel-main.edn` file.\n\n"
         "# Commonly used options (in order of importance)\n\n"
         (markdown-option-docs common)
         "# Rarely used options\n\n"
         (markdown-option-docs un-common))))

(defn output-docs [output-to]
  (.mkdirs (.getParentFile (io/file output-to)))
  (spit output-to (markdown-docs)))

#_(output-docs "doc/figwheel-main-options.md")

#_(markdown-docs)
