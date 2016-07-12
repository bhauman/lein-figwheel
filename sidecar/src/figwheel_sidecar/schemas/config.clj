(ns figwheel-sidecar.schemas.config
  (:refer-clojure :exclude [boolean?])
  (:require
   [strictly-specking-standalone.spec :as s]
   [figwheel-sidecar.schemas.cljs-options :as cljs-opt]
   [strictly-specking-standalone.core :refer [strict-keys
                                              def-key
                                              attach-reason
                                              non-blank-string?]
    :as ssp]))

#_ (remove-ns 'figwheel-sidecar.schemas.config)

;; for development - not a crime if it happens in production
(ssp/reset-duplicate-keys)

;; !!! ADDING for 1.8 compat
(defn boolean?
  "Return true if x is a Boolean"
  {:added "1.9"}
  [x] (instance? Boolean x))


(def-key ::string-or-symbol (some-fn non-blank-string? symbol?))

(def-key ::string-or-named  (some-fn non-blank-string? keyword? symbol?))

#_(s/conform ::string-or-named :asdfasdf)

#_(s/explain (s/every string? :min-count 1 :into [] :kind vector?)
           {:asdf 1})

;; * Figwheel Configuration

;; ** In the Leiningen project.clj

;; The most common way to configure lein-figwheel is is in the
;; Leiningen project.clj which is normally found at the root of your
;; clojure project directory

;; *** :figwheel Figwheel System Options

;; To supply configuration options to the figwheel system place the
;; following options in a Map under the :figwheel key at the root or
;; the project.clj

(def-key :figwheel.lein-project/figwheel
  (strict-keys
   :opt-un
   [::http-server-root
    ::server-port
    ::server-ip
    ::css-dirs
    ::ring-handler
    ::builds-to-start
    ::server-logfile
    ::open-file-command
    ::repl
    ::nrepl-port
    ::nrepl-host
    ::nrepl-middleware
    ::validate-config
    ::validate-interactive    
    ::load-all-builds
    ::ansi-color-output
    ::builds
    ::reload-clj-files    
    ::hawk-options])
  "A Map of options that determine the behavior of the Figwheel system.

  :figwheel {
    :css-dirs [\"resources/public/css\"]
  }")

(def-key ::http-server-root  non-blank-string?

  "Figwheel relies on the compojure.route/resources handler to serve
static files. This serves resources on the classpath from a specific
subdirectory. The default is \"public\" so any file on the resource path
in a \"public\" subdirectory is capable of being served.

You can change the default \"public\" to something else. But you can't
have a blank :http-server-root this can lead to insidious compiler
problems because the compiler also looks up resources when it looks
for source files.
Default: \"public\"

  :http-server-root \"public\"")

(def-key ::server-port       integer?

  "An integer that the figwheel HTTP and Websocket server should bind.
Default: 3449

  :server-port 3000")

(def-key ::server-ip         non-blank-string?
  "The network interface that the figwheel server will listen on. This is
useful if you don't want to use a public network interface.
Default: \"localhost\"

  :server-ip \"0.0.0.0\"")

(def-key ::css-dirs  (s/every non-blank-string?
                              :min-count 1
                              :into []
                              :kind sequential?)

  "A vector of paths from the project root to the location of your css
files. These files will be watched for changes and the figwheel client
will attempt to reload them.

  :css-dirs [\"resources/public/css\"]")

(def-key ::ring-handler      ::string-or-named

  "If you want to embed a ring handler into the figwheel http-kit server;
this is for simple ring servers, if this doesn't work for you just run
your own server.
Default: Off

  :ring-handler example.core/my-server-handler")

(def-key ::builds-to-start (s/every ::string-or-named
                                    :min-count 1
                                    :into []
                                    :kind sequential?)

  "A vector of build ids that you would like figwheel to start building
when you invoke lein figwheel without arguments.

  :builds-to-start [\"dev\" \"test\"]")

(def-key ::server-logfile    non-blank-string?)

(def-key ::open-file-command non-blank-string?
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

(def-key ::repl              boolean?

  "A Boolean value indicated wether to run a ClojureScript REPL after the
figwheel process has launched.
Default: true

  :repl false")

(def-key ::nrepl-port        integer?

  "An integer indicating that you would like figwheel to launch nREPL
from within the figwheel process and what port you would like it to
launch on.
Default: off

  :nrepl-port 7888")

(def-key ::nrepl-host        non-blank-string?

  "If the :nrepl-port is provided Figwheel will launch an nREPL server
into the figwheel compilation process.  :nrepl-host is a string which
specifies which local network interface you want to launch the server on.

  :nrepl-host \"localhost\"")

(def-key ::nrepl-middleware  (s/every ::string-or-named :min-count 1 :into [] :kind sequential?)

  "A vector of strings indicating the nREPL middleware you want included
when nREPL launches.

  :nrepl-middleware [\"cider.nrepl/cider-middleware\" \"cemerick.piggieback/wrap-cljs-repl\"]")

(def-key ::validate-config   (some-fn boolean? #{:warn-unknown-keys :ignore-unknown-keys})

  "Change configuration validation behavior.

The possible values are:

true or false        - to turn config validation on or off.
:warn-unknown-keys   - only print a warning when unknown keys are encountered
:ignore-unknown-keys - do nothing when unknown keys are encountered

Default: true

  :validate-config false")

(def-key ::validate-interactive (some-fn boolean? #{:fix :quit})
  "Because build startup time is significant Figwheel offers the
opportunity for you to fix you configuration problems interactively.
In some develeopment environments this is not desirable. In this case
you can just set :validate-interactive to false

Other options are:
:fix   - don't ask if you want to watch and fix, just start watching
:quit  - just quit on a validation error, same behavior as false
:start - ignore any configuration errors and start figwheel anyway

Default: true

  :validate-interactive false" )

(def-key ::load-all-builds   boolean?

  "A Boolean value that specifies wether or not to load all the
ClojureScript builds that are available in your config.  When these
builds are loaded all of their source paths are added to the classpath.

This can result in load conflicts if you are overloading a Clojure
namespace.

If your project.clj contains conflicting builds, you can choose to
only load the builds specified on the command line or in
:builds-to-start by setting :load-all-builds to false.")

(def-key ::ansi-color-output boolean?

  "Figwheel makes an effort to provide colorful text output. If you need
to prevent ANSI color codes in figwheel output set :ansi-color-output
to false.  Default: true

  :ansi-color-output false")

(def-key ::hawk-options (s/map-of #{:watcher} #{:barbary :java :polling})

  "If you need to watch files with polling instead of FS events. This can
be useful for certain docker environments.

  :hawk-options {:watcher :polling}" )

(def-key ::reload-clj-files
  (s/or
   :bool boolean?
   :suffix-map
   (strict-keys
    :opt-un
    [::clj ::cljc]))

  "Figwheel naively reloads clj and cljc files on the :source-paths.
It doesn't reload clj dependent files like tools.namspace.

Figwheel does note if there is a macro in the changed clj or cljc file
and then marks any cljs namespaces that depend on the clj file for
recompilation and then notifies the figwheel client that these
namespaces have changed.

If there is no macro in the clj/cljc namespace figwheel marks all cljs files
for recompilation.

If you want to disable this behavior:

  :reload-clj-files false

Or you can specify which suffixes will cause the reloading

  :reload-clj-files {:clj true :cljc false}")

;; the following are only for the :reload-clj-files Map
(def-key ::clj  boolean?)
(def-key ::cljc boolean?)

;; *** Build Configurations

;; The primary configuration information for Figwheel are the build
;; configuration definitions.

;; These determine
;; - what source files the CLJS compiler will compile
;; - how they should be compiler
;; - where the compiled files should be placed

;; Build configuration can be found in the project.clj under either
;; the :cljsbuild > :builds key or the :figwheel > :builds key.

;; Because lein-figwheel needs ClojureScript build configurations just
;; like lein-cljsbuild Figwheel will re-use the build configurations
;; in :cljsbuild > :builds. This is the most common place to put your
;; build configurations.

(def-key :cljsbuild.lein-project/cljsbuild
  (strict-keys
   :opt-un [::builds
            ::repl-listen-port
            ::repl-launch-commands
            ::test-commands
            ::crossovers
            ::crossover-path
            ::crossover-jar]))

;; spec out other lein cljsbuild options to provide comprehensive
;; configuration validation
(def-key ::repl-listen-port      integer?)
(def-key ::repl-launch-commands
  (s/map-of ::string-or-named
            (s/every ::string-or-named :into [] :kind sequential?)))
(def-key ::test-commands
  (s/map-of ::string-or-named
            (s/every ::string-or-named :into [] :kind sequential?)))
(def-key ::crossovers            (s/every ::s/any :into [] :kind sequential?))
(def-key ::crossover-path        (s/every ::s/any :into [] :kind sequential?))
(def-key ::crossover-jar         boolean?)

;; If :cljsbuild > :builds is not present you must have :figwheel > :builds

;; The ::builds key can be either a map of ::build-configs or vector of
;; ::build-configs. Again it can be placed under :cljsbuild or :figwheel
;; it also can be found at the top level of a figwheel.edn file

(def-key ::builds
  (s/or                
   :builds-vector (s/every ::build-config-require-id :min-count 1 :into [] :kind sequential?)
   :builds-map  (s/every-kv ::string-or-named ::build-config
                            :kind map?
                            :min-count 1))

  "A Vector or Map of ClojureScript Build Configurations.

  :builds [{:id \"dev\"
            :source-paths [\"src\"]
            :figwheel true
            :compiler {:main example.core
                       :asset-path \"js/out\"
                       :output-to \"resources/public/example.js\"
                       :output-dir \"resources/public/out\"}}]

   or

  :builds {:dev {:source-paths [\"src\"]
                 :figwheel true
                 :compiler {:main example.core
                            :asset-path \"js/out\"
                            :output-to \"resources/public/example.js\"
                            :output-dir \"resources/public/out\"}}}")

(defn opt-none-build [x]
  (and
   (map? x)
   (:compiler x)
   (let [c (:compiler x)]
     (or (not (contains? c :optimizations))
         (nil? (:optimizations c))
         (= (:optimizations c) :none)))))

(def-key ::build-config
  (s/and
   ;; first so that it isn't conformed value
   (attach-reason
    "A Figwheel build must have :compiler > :optimizations default to nil or set to :none"
    (fn [{:keys [figwheel] :as build-config}]
      (if figwheel #_(second figwheel)
        (opt-none-build build-config)
        true))
    :focus-path [:compiler :optimizations])
   (strict-keys
    :opt-un
    [::id
     ::notify-command
     ::jar
     ::incremental
     ::assert
     ::warning-handlers
     ::figwheel]
    :req-un
    [::source-paths
     ::compiler]))

  "A Map of options that specifies a ClojureScript 'build'

   {:id \"dev\"
    :source-paths [\"src\"]
    :figwheel true
    :compiler {:main example.core
               :asset-path \"js/out\"
               :output-to \"resources/public/example.js\"
               :output-dir \"resources/public/out\"}}")


#_ (alias 'parse 'strictly-specking-standalone.parse-spec)
#_ (parse/find-key-path-without-ns ::build-config :optimzations)

#_ (ssp/explain ::builds {:asdf
                          {:figwheel true
                           :source-paths ["src"]
                           :compiler {:output-to "main.js"
                                      :optimizations :advanced}}})

(comment

  (alias 'ep 'strictly-specking-standalone.error-printing)

  (let [x {:asdf
           {:figwheel true
            :source-paths ["src"]
            :compiler {:output-to "main.js"
                       :optimizations :advanced}}}
        exd (s/explain-data ::builds x)
        e (first (ssp/prepare-errors exd x nil))]
    (ep/pprint-inline-message e)
    (ssp/dev-print exd x nil)
    
    )

)

;; When you use a vector to define your :builds you have to supply an :id
(def-key ::build-config-require-id
  (s/and
   map?
   #(contains? % :id)
   ::build-config)

  "A Map of options that specifies a ClojureScript 'build'

  {:id \"dev\"
   :source-paths [\"src\"]
   :figwheel true
   :compiler {:main example.core
              :asset-path \"js/out\"
              :output-to \"resources/public/example.js\"
              :output-dir \"resources/public/out\"}}")

;; **** Build Config options

(def-key ::id               ::string-or-named

  "A Keyword, String or Symbol that identifies this build.

  :id \"dev\"")

(def-key ::source-paths (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "A vector of paths to your cljs source files. These paths should be
relative from the root of the project to the root the namespace.
For example, if you have an src/example/core.cljs file that contains a
example.core namespace, the source path to this file is \"src\"

  :source-paths [\"src\"]")

(def-key ::figwheel
  (s/or
   :bool boolean?
   :figwheel-client-options
   (strict-keys
    :opt-un
    [::build-id
     ::websocket-host
     ::websocket-url
     ::on-jsload
     ::before-jsload
     ::on-cssload
     ::on-message
     ::on-compile-fail
     ::on-compile-warning
     ::reload-dependents
     ::debug
     ::autoload
     ::heads-up-display
     ::load-warninged-code
     ::retry-count
     ::devcards
     ::eval-fn
     ::open-urls]))
  "Either the Boolean value true or a Map of options to be passed to the
figwheel client. Supplying a true value or a map indicates that you
want the figwheel client code to be injected into the build.

  :figwheel true")

(def-key ::compiler ::cljs-opt/compiler-options
  "The options to be forwarded to the ClojureScript Compiler

Please refer to

  :compiler {:main example.core
             :asset-path \"js/out\"
             :output-to \"resources/public/example.js\"
             :output-dir \"resources/public/out\"}")

(def-key ::notify-command   (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "If a :notify-command is specified, it will be called when compilation
succeeds or fails, and a textual description of what happened will be
appended as the last argument to the command. If a more complex
command needs to be constructed, the recommendation is to write a
small shell script wrapper.
Default: nil (disabled)

  :notify-command [\"growlnotify\" \"-m\"]")

;; we are supporting lein-cljsbuild build-config options to provide
;; more comprehensive validation

(def-key ::jar              boolean?)
(def-key ::incremental      boolean?)
(def-key ::assert           boolean?)
(def-key ::warning-handlers (s/every ::s/any :min-count 1 :into [] :kind sequential?))

;; **** Figwheel Client Options

;; Figwheel client options are provided under the :figwheel key in a
;; build configuration

(def-key ::build-id             non-blank-string?

  "A Keyword, String or Symbol that identifies this build.

  :build-id \"dev\"")

(def-key ::websocket-host (s/or :string non-blank-string?
                                :host-option #{:js-client-host :server-ip :server-hostname})

 "A String specifying the host part of the Figwheel websocket URL. If you have
JavaScript clients that need to access Figwheel that are not local, you can
supply the IP address of your machine here, or you can specify one of the
following keywords to have figwheel determine the string for you.
  :js-client-host  -- will use js/window.location.host
  :server-ip       -- will use local IP address of figwheel server
  :server-hostname -- will do a local hostname lookup on figwheel server
Default: \"localhost\"

  :websocket-host :server-ip")

;; **** TODO we can detect malformed and misspelled tags ... fun!!!
;; in websocket-url we can do fine grained parsing to detect malformed urls

(comment
  (defmacro str-regex [s]
  `(s/cat ~@(apply
             concat
             (map-indexed
              (fn [i a]
                [(keyword (str "ch" i))
                 (set (vector a))])
              s))))

  (s/def ::url-tag (s/cat
                 :open-brackets  (str-regex "[[")
                 :tag-name (s/alt
                            :server-hostname (str-regex "server-hostname")
                            :server-ip (str-regex "server-ip")
                            :server-port (str-regex "server-port")
                            :client-hostname (str-regex "client-hostname")
                            :client-port (str-regex "client-port"))
                 :close-brackets (str-regex "]]")))

  (def host-alpha (let [alpha "abcdefghikjlmnopqrstuvwxyz"]
                    (set (str alpha (clojure.string/upper-case alpha) "0123456789" ".-"))))

  (s/def ::hostname (s/+ host-alpha))

  (s/explain-data (s/cat :protocol
                         (s/cat :ws (str-regex "ws") :secure-s (s/? #{\s}))
                         :colon #{\:}
                         :slashes (str-regex "//")
                         :tag ::url-tag
                         ) (seq "ws://[[server-host"))

)

#_(s/explain (s/cat :a #{\w} :b #{\s} :c (s/? #{\s})
                  :e #{\:}
                  :d #{\/} :f #{\/})
           (seq "ws:/"))



(def-key ::websocket-url        non-blank-string?

  "You can override the websocket url that is used by the figwheel client
by specifying a :websocket-url

The value of :websocket-url is usually
  :websocket-url \"ws://localhost:3449/figwheel-ws\"

The :websocket-url is normally derived from the :websocket-host option.
If you supply a :websocket-url the :websocket-host option will be ignored.

The :websocket-url allows you to use tags for common dynamic values.
For example in:
  :websocket-url \"ws://[[client-hostname]]:[[server-port]]/figwheel-ws\"

Figwheel will fill in the [[client-hostname]] and [[server-port]] tags

Available tags are:
  [[server-hostname]] ;; supplies the detected server hostname
  [[server-ip]]       ;; supplies the detected server ip
  [[server-port]]     ;; supplies the figwheel server port
  [[client-hostname]] ;; supplies the current hostname on the client
  [[client-port]]     ;; supplies the current hostname on the client")

(def-key ::on-jsload            ::string-or-named

  "A String or Symbol representing a client side ClojureScript function
to be invoked after new code has been loaded.
Default: Off

  :on-jsload \"example.core/fig-reload\"")

(def-key ::heads-up-display     boolean?

  "Show a notification in the browser on each refresh.
Default: true

  :heads-up-display false")

(def-key ::load-warninged-code  boolean?

  "If there are warnings in your code emitted from the compiler, figwheel
does not refresh. If you would like Figwheel to load code even if
there are warnings generated set this to true.
Default: false

  :load-warninged-code true")

(def-key ::open-urls   (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "A Vector of URLs that you would like to have opened at the end of the
initial compile. These URLs must be Strings.

This is great for opening the host page for the target build and other
helpful websites like http://cljs.info

These urls will be opened with clojure.java.browse/browse-url.
Default: nil (disabled)

  :open-urls")

;; TODO fill out the docs below

(def-key ::before-jsload        ::string-or-named)
(def-key ::on-cssload           ::string-or-named)
(def-key ::on-message           ::string-or-named)
(def-key ::on-compile-fail      ::string-or-named)
(def-key ::on-compile-warning   ::string-or-named)
(def-key ::eval-fn              ::string-or-named)

(def-key ::reload-dependents    boolean?)
(def-key ::debug                boolean?)
(def-key ::autoload             boolean?)
(def-key ::devcards             boolean?)
(def-key ::retry-count          integer?)


;; * Conditional Top Level Specs

;; *** Leiningen Project
;; we have different situations

;; normal situation is :cljsbuild with required builds

(defn lein-project-spec [project]
  ;; TODO this needs fuzzy key detection
  (cond
    (get-in project [:figwheel :builds]) ::lein-project-with-figwheel-builds
    (not (get-in project [:cljsbuild]))  ::lein-project-only-figwheel
    :else ::lein-project-with-cljsbuild))

(defn known-build-ids [{:keys [cljsbuild figwheel]}]
  (let [g (or (:builds figwheel) (:builds cljsbuild))
        builds (if (map? g) (map (fn [[k v]] (assoc v :id k)) g) g)
        v (filter opt-none-build builds)]
    (set (if (map? v) (keys v) (keep :id v)))))

(defn get-builds-to-start-not-in-build-ids [{:keys [cljsbuild figwheel] :as proj}]
  (let [build-ids (known-build-ids proj)]
    (filter (complement build-ids) (:builds-to-start figwheel))))

(defn builds-to-start-ids-must-be-in-builds [{:keys [cljsbuild figwheel] :as proj}]
  (if (not-empty (:builds-to-start figwheel))
    (empty? (get-builds-to-start-not-in-build-ids proj))
    true))

(def-key ::lein-project-with-cljsbuild
  (s/and
   map?
   ;; TODO once this works add it to other projects
   (attach-reason
    (fn [proj]
      (when-let [ky (first
                     (get-builds-to-start-not-in-build-ids proj))]
        (str
         "The ids in :builds-to-start must identify optimizations :none build configs.\n"
         "The id " (pr-str ky) " is not the id of a opt' :none build config.\n"
         "The only known opt' :none build configs are " (pr-str (known-build-ids proj)))))
    builds-to-start-ids-must-be-in-builds
    :focus-path (fn [project]
                  (if-let [idx (when-let [ky 
                                          (first
                                           (get-builds-to-start-not-in-build-ids project))]
                                 (.indexOf (vec (-> project :figwheel :builds-to-start))
                                           ky))]
                    [:figwheel :builds-to-start idx]
                    [:figwheel :builds-to-start])))
   (strict-keys
    :opt-un [:figwheel.lein-project/figwheel]
    :req-un [:cljsbuild.lein-project.require-builds/cljsbuild])))

#_(ssp/explain ::lein-project-with-cljsbuild
               {:cljsbuild {:builds {:asdfg {
                                            :source-paths ["src"]
                                             :compiler {:output-to "main.js"
                                                        :optimizations :whitespace}}
                                     :asdf {
                                            :source-paths ["src"]
                                            :compiler {:output-to "main.js"}}}}
                :figwheel {:builds-to-start [:asdf :asdf :Asdff :Asdf]}})

#_(defn find-doc-keyword [e]
  (->> e ::ssp/error-path :in-path reverse (filter keyword?) first
       ))

#_(->
 (ssp/explain-data ::lein-project-with-cljsbuild
               {:cljsbuild {:builds {:asdfg {
                                            :source-paths ["src"]
                                             :compiler {:output-to "main.js"
                                                        :optimizations :whitespace}}
                                     :asdf {
                                            :source-paths ["src"]
                                            :compiler {:output-to "main.js"}}}}
                :figwheel {:builds-to-start [:asdf :asdf :Asdff :Asdf]}})
 ::s/problems
 first
 find-doc-keyword
 #_ssp/keys-to-document
 )

(comment

  (alias 'ep 'strictly-specking-standalone.error-printing)

  ;; TODO consider putting all extra data in the meta of the err
  (let [x {:cljsbuild {:builds {:asdf {
                                       :source-paths ["src"]
                                       :compiler {:output-to "main.js"}}}}
                   :figwheel {:builds-to-start [:asdff]}}
        exd (s/explain-data ::lein-project-with-cljsbuild x)
        e (first (ssp/prepare-errors exd x nil))]
    (ep/pprint-inline-message e)
    (ssp/dev-print exd x nil)
    
    )

)


;; TODO should write a test like this for each key
#_(parse/find-key-path-without-ns ::lein-project-with-cljsbuild :cljc)

;; if only figwheel is available

(def-key ::lein-project-only-figwheel
  (strict-keys
   :req-un [:figwheel.lein-project.require-builds/figwheel]))

;; if figwheel is available with builds in it

(def-key ::lein-project-with-figwheel-builds
  (strict-keys
   :opt-un [:figwheel.lein-project.require-builds/figwheel]
   ;; don't require builds in cljsbuild
   :req-un [:cljsbuild.lein-project/cljsbuild]))

(defn must-have-one-opt-none-build [with-builds]
  (if (and (or (map? (:builds with-builds))
               (vector? (:builds with-builds)))
           (pos? (count (:builds with-builds)))
           (every? (if (map? (:builds with-builds))
                     #{:compiler :source-paths}
                     #{:compiler :source-paths :id})
                   (:builds with-builds)))
    (let [v (:builds with-builds)]
      (let [blds (if (map? v) (vals v) v)]
        (some opt-none-build blds)))
    true))

(def must-have-one-opt-none-build-spec
  (attach-reason "Figwheel needs at least one build with :optimizations set to :none or nil"
                 must-have-one-opt-none-build
                 :focus-path [:builds]))

;; TODO this ordering is because and flows conformed values
(def-key :cljsbuild.lein-project.require-builds/cljsbuild
  (s/and
   map?
   #(contains? % :builds)
   must-have-one-opt-none-build-spec
   :cljsbuild.lein-project/cljsbuild))

#_(ssp/explain :cljsbuild.lein-project.require-builds/cljsbuild
               {:builds [{:id "asdf" :source-paths ["src"]
                          :compiler
                          {:output-to "main.js"
                           :optimizations :whitespace}}]})

(def-key :figwheel.lein-project.require-builds/figwheel
  ;; wait for merge to not propogate conformed values
  (s/and
   map?
   #(contains? % :builds)   
   must-have-one-opt-none-build-spec
   :figwheel.lein-project/figwheel))

;; ** figwheel.edn

(def-key ::figwheel-edn :figwheel.lein-project.require-builds/figwheel
  "If a figwheel.edn file is present at the root of your directory
figwheel will use this file as a configuration source.

The structure of the EDN in figwheel.edn file is the same as the
structure of the top level :figwheel key in the project.clj

Example figwheel.edn file

  {:server-port 4000
   :http-server-root \"public\"
   :css-dirs [\"resources/public/css\"]
   :builds {:dev {:id \"dev\"
                  :source-paths [\"src\"]
                  :figwheel true
                  :compiler {:main example.core
                             :asset-path \"js/out\"
                             :output-to \"resources/public/example.js\"
                             :output-dir \"resources/public/out\"}}}}")

;; ** Figwheel Internal

;; co-dependency - must exist in all-builds
(def-key ::build-ids  (s/every ::string-or-named :into [] :kind sequential?))

(def-key ::figwheel-options :figwheel.lein-project/figwheel)

(def-key ::all-builds (s/every ::build-config-require-id :min-count 1 :into [] :kind sequential?))

(def-key ::figwheel-internal-config
  (strict-keys
   :req-un [::all-builds]
   :opt-un [::figwheel-options
            ::build-ids]))

(comment
  (def test-data
    { :cljsbuild {
                  :builds {:dev {:id "example-admin"
                                 :source-paths ["src" "dev" "tests" "../support/src"]
                                 ;:notify-command ["notify"]
                                 :assert true



                           
                                 :compiler { :main 'example.core
                                             :asset-path "js/out"
                                             :output-to "resources/public/js/example.js"
                                             :output-dir "resources/public/js/out"
                                             :libs ["libs_src" "libs_sscr/tweaky.js"]
                                             ;; :externs ["foreign/wowza-externs.js"]
                                             :foreign-libs [{:file "foreign/wowza.js"
                                                             :provides ["wowzacore"]}]
                                             ;; :recompile-dependents true
                                             ;; :source-map true
                                            :optimizations :whitespace
                                 :figwheel
                                 {:websocket-host "localhost"
                                  :on-jsload      'example.core/fig-reload
                                  :on-message     'example.core/on-message
                                  :open-urls ["http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"]
                                  :source-map true
                                  :debug true
                                  }
                                            }}
                           #_:asdf1 #_{:id "example-admin"
                                 :source-paths
                                 ["src" "dev" "tests" "../support/src"]
                                 :notify-command 3 #_["notify"]
                                 :assert true
                                 :figwheel
                                 {:websocket-host "localhost"
                                  :on-jsload      'example.core/fig-reload
                                  :on-message     'example.core/on-message
                                  :open-urls ["http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                              "http://localhost:3449/index.html"
                                                          "http://localhost:3449/index.html"]
                                  :debug true
                                  }
                           
                                 :compiler { :main 'example.core
                                            :asset-path "js/out"
                                            :output-to "resources/public/js/example.js"
                                            :output-dir "resources/public/js/out"
                                            :libs ["libs_src" "libs_sscr/tweaky.js"]
                                            ;; :externs ["foreign/wowza-externs.js"]
                                            :foreign-libs [{:file "foreign/wowza.js"
                                                            :provides ["wowzacore"]}]
                                            ;; :recompile-dependents true
                                            :optimizations :none}}
                           } #_[{:id "example-admin"
                                 :source-paths
                                 ["src" "dev" "tests" "../support/src"]
                                 :notify-command :Asdfasdf #_["notify"]
                                 :assert true
                                 :figwheel
                                 {:websocket-host "localhost"
                                  :on-jsload      'example.core/fig-reload
                                  :on-message     'example.core/on-message
                                  :open-urls {:Asdf 1} #_[1"http://localhost:3449/index.html"
                                                          "http://localhost:3449/index.html"
                                                          "http://localhost:3449/index.html"
                                                          "http://localhost:3449/index.html"
                                                          "http://localhost:3449/index.html"]
                                  :debug true
                                  }
                           
                                 :compiler { :main 'example.core
                                            :asset-path "js/out"
                                            :output-to "resources/public/js/example.js"
                                            :output-dir "resources/public/js/out"
                                            :libs ["libs_src" "libs_sscr/tweaky.js"]
                                            ;; :externs ["foreign/wowza-externs.js"]
                                            :foreign-libs [{:file "foreign/wowza.js"
                                                            :provides ["wowzacore"]}]
                                            ;; :recompile-dependents true
                                            :optimizations :none}}
                           

                                { :id "example"
                                 :source-paths ["src" "dev" "tests" "../support/src"]
                                 :notify-command ["notify"]
                                 :figwheel
                                 { :websocket-host "localhost"
                                  :on-jsload      'example.core/fig-reload
                                  :on-message     'example.core/on-message
                                  :open-urls ["http://localhost:3449/index.html"]
                                  :debug true
                                  }
                                 :compiler { :main 'example.core
                                            :asset-path "js/out"
                                            :output-to "resources/public/js/example.js"
                                            :output-dir "resources/public/js/out"
                                            :libs ["libs_src" "libs_sscr/tweaky.js"]
                                            ;; :externs ["foreign/wowza-externs.js"]
                                            :foreign-libs [{:file "foreign/wowza.js"
                                                            :provides ["wowzacore"]}]
                                            ;; :recompile-dependents true
                                            :optimizations :none}}]}})

  (ssp/dev-print (s/explain-data ::lein-project-with-cljsbuild
                                test-data)
                test-data
                nil)

#_(ssp/prepare-errors (s/explain-data (ssp/non-empty-map-of keyword? (s/map-of keyword? integer?))
                                   {})
                   {}
                   nil)
  
  (first (ssp/prepare-errors (s/explain-data ::lein-project-with-cljsbuild
                                            test-data)
                            test-data
                            nil))
 
  )



(comment



  (s/explain ::lein-project-with-cljsbuild
             test-data)

  (s/explain ::build-config (get-in test-data [:cljsbuild :builds 0]))

  (s/def ::string  (s/or :string string?))

  (s/def ::myid ::string)

  (s/explain (s/merge
              (s/keys :opt-un [::myid])
              (s/spec (fn [x] (prn x) true))
              (s/keys :req-un [::myid]))
             {:myid "asdf"})

  (s/explain (s/and
              (s/or :int integer?)
              (s/spec (fn [x] (prn x)
                        true))
              )
             5)
  )
