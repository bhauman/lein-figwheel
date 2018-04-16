(ns figwheel-sidecar.schemas.cljs-options
  (:refer-clojure :exclude [boolean?])
  (:require
   [strictly-specking-standalone.spec :as s]
   [clojure.string :as string]
   [strictly-specking-standalone.core :refer [def-key
                                   strict-keys
                                   attach-reason
                                   attach-warning
                                   non-blank-string?
                                   reset-duplicate-keys]]
   [clojure.test :refer [deftest is testing]]))

#_ (remove-ns 'strictly-specking-standalone.cljs-options-schema)

;; !!! ADDING for 1.8 compat
(defn boolean?
  "Return true if x is a Boolean"
  {:added "1.9"}
  [x] (instance? Boolean x))

;; for development
(reset-duplicate-keys)

;; * Specification for ClojureScript
;; ** Top level util specs

(def-key ::string-or-symbol (some-fn string? symbol?))
(def-key ::string-or-named  (some-fn string? symbol? keyword?))

;; ** CLJS Compiler Options
;; *** Commonly used Compiler Options
;; **** TODO Look at the use of clojure.spec/+

;; The clojure.spec/+ is used in favor of clojure.spec/* below.  The
;; assumption is that we want to look at the indivudiual use cases and
;; decide if an empty expression actually makes sense.

;; This conflates the use case of options compliance and creating a
;; stricter set of options to inform users of possible missuse.

;; I think it's better to have a tighter expression and force folks to
;; remove/comment out no-op configs, to bring awareness to them.
;; Commented out configs are more obvious than no-op ones.

(def-key ::output-to non-blank-string?

  "After your ClojureScript has been compiled to JavaScript, this
specifies the name of the JavaScript output file.  The contents of
this file will differ based on the :optimizations setting.

If :optimizations is set to :none then this file will merely contain
the code needed to load Google Closure the and rest of the compiled
namespaces (which are separate files).

If :optimizations is set to :simple, :whitespace, or :advanced this
output file will contain all the compiled code.

  :output-to \"resources/public/js/main.js\"")

(def-key ::output-dir non-blank-string?

  "Sets the output directory for output files generated during
compilation.

Defaults to  \"out\".

  :output-dir \"resources/public/js/out\"")

(def-key ::optimizations #{:none :whitespace :simple :advanced}

  "The optimization level. May be :none, :whitespace, :simple, or
:advanced. Only :none and :simple are supported for bootstrapped
ClojureScript.

  :none is the recommended setting for development

  :advanced is the recommended setting for production, unless
        something prevents it (incompatible external library, bug,
        etc.).

For a detailed explanation of the different optimization modes see
https://developers.google.com/closure/compiler/docs/compilation_levels

When the :main option is not used, :none requires manual code loading
and hence a separate HTML from the other options.

Defaults to :none. Note: lein cljsbuild 1.0.5 will supply :whitespace.

  :optimizations :none")

(def-key ::main                      ::string-or-symbol

  "Specifies an entry point namespace. When combined with optimization
level :none, :main will cause the compiler to emit a single JavaScript
file that will import goog/base.js, the JavaScript file for the
namespace, and emit the required goog.require statement. This permits
leaving HTML markup identical between dev and production.

Also see :asset-path.

  :main \"example.core\"")

(def-key ::asset-path                string?

  "When using :main it is often necessary to control where the entry
point script attempts to load scripts from due to the configuration of
the web server. :asset-path is a relative URL path not a file system
path. For example, if your output directory is :ouput-dir
\"resources/public/js/compiled/out\" but your webserver is serving files
from \"resources/public\" then you want the entry point script to load
scripts from \"js/compiled/out\".

  :asset-path \"js/compiled/out\"")

(def-key ::source-map                (some-fn boolean? non-blank-string?)

  "See https://github.com/clojure/clojurescript/wiki/Source-maps. Under
optimizations :none the valid values are true and false, with the
default being true. Under all other optimization settings must specify
a path to where the source map will be written.

Under :simple, :whitespace, or :advanced
  :source-map \"path/to/source/map.js.map\"")


(def-key ::preloads                  (s/every symbol? :min-count 1 :into [] :kind sequential?)

  "Developing ClojureScript commonly requires development time only
side effects such as enabling printing, logging, spec instrumentation,
and connecting REPLs. :preloads permits loading such side effect
boilerplate right after cljs.core. For example you can make a
development namespace for enabling printing in browsers:

  (ns foo.dev)

  (enable-console-print!)

Now you can configure your development build to load this side effect
prior to your main namespace with the following compiler options:

  {:preloads [foo.dev]
   :main \"foo.core\"
   :output-dir \"out\"}

The :preloads config value must be a sequence of symbols that map to
existing namespaces discoverable on the classpath.")

(def-key ::verbose                   boolean?

  "Emit details and measurements from compiler activity.

  :verbose true")

(def-key ::pretty-print              boolean?

  "Determines whether the JavaScript output will be tabulated in a
human-readable manner. Defaults to true.

  :pretty-print false")

(def-key ::target                    #{:nodejs :webworker}

  "If targeting nodejs add this line. Takes no other options at the
moment. The default (no :target specified) implies browsers are being
targeted. Have a look here for more information on how to run your
code in nodejs.

  :target :nodejs")

(def-key ::infer-externs boolean?
  "Experimental externs inference.

  :infer-externs true

When you set :infer-externs true you will get a new file in
your :output-dir named inferred_externs.js. When you do an advanced
build, this externs file will be used.

You must add type hints to your code as such:

  (set! *warn-on-infer* true)
  (defn foo [^js/React.Component c]
    (.render c))

Please see:
https://gist.github.com/swannodette/4fc9ccc13f62c66456daf19c47692799")


(def-key ::foreign-libs (s/every
                         (strict-keys
                          :req-un [::file]
                          :opt-un [::file-min
                                   ::provides
                                   ::requires
                                   ::module-type
                                   ::preprocess
                                   ::global-exports])
                         :into []
                         :kind sequential?)

  "Adds dependencies on foreign libraries. Be sure that the url returns a
HTTP Code 200

Defaults to the empty vector []

  :foreign-libs [{ :file \"http://example.com/remote.js\"
                   :provides  [\"my.example\"]}]

Each element in the :foreign-libs vector should be a map, where the
keys have these semantics:

  :file Indicates the URL to the library

  :file-min (Optional) Indicates the URL to the minified variant of
            the library.

  :provides A synthetic namespace that is associated with the library.
            This is typically a vector with a single string, but it
            has the capability of specifying multiple namespaces
            (typically used only by Google Closure libraries).

  :requires (Optional) A vector explicitly identifying dependencies
            (:provides values from other foreign libs); used to form a
            topological sort honoring dependencies.

  :module-type (Optional) indicates that the foreign lib uses a given
               module system. Can be one of :commonjs, :amd, :es6.
               Note that if supplied, :requires is not used (as it is
               implicitly determined).

  :preprocess (Optional) Used to preprocess / transform code in other
              dialects (JSX, etc.). A defmethod for
              cljs.clojure/js-transforms must be provided that matches
              the supplied value in order to effect the desired code
              transformation.

  :global-exports (Optional) used to map provided namespaces to
                  globally exported values. If present the foreign
                  library can be used idiomatically when required,
                  i.e. support for :refer, :rename, :as, etc.")

(def-key ::file        non-blank-string?)
(def-key ::provides    (s/every non-blank-string? :min-count 1 :into [] :kind sequential?))
(def-key ::file-min    non-blank-string?)
(def-key ::requires    (s/every non-blank-string? :min-count 1 :into [] :kind sequential?))
(def-key ::module-type #{:commonjs :amd :es6})
(def-key ::preprocess  ::string-or-named)
(def-key ::global-exports (s/map-of ::string-or-named ::string-or-named))

(def-key ::externs     (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "Configure externs files for external libraries.

For this option, and those below, you can find a very good explanation at:
http://lukevanderhart.com/2011/09/30/using-javascript-and-clojurescript.html

Defaults to the empty vector [].

  :externs [\"jquery-externs.js\"]")

(def-key ::modules (s/map-of
                    keyword?
                    (strict-keys
                     :req-un [:cljs.options-schema.modules/output-to]
                     :opt-un [::entries
                              ::depends-on]))

  "A new option for emitting Google Closure Modules. Closure Modules
supports splitting up an optimized build into N different modules. If
:modules is supplied it replaces the single :output-to. A module needs
a name, an individual :output-to file path, :entries a set of
namespaces, and :depends-on a set of modules on which the module
depends. Modules are only supported with :simple and :advanced
optimizations. An example follows:

  {:optimizations :advanced
   :source-map true
   :output-dir \"resources/public/js\"
   :modules {
     :common
       {:output-to \"resources/public/js/common.js\"
        :entries #{\"com.foo.common\"}}
     :landing
       {:output-to \"resources/public/js/landing.js\"
        :entries #{\"com.foo.landing\"}
        :depends-on #{:common}}
     :editor
       {:output-to \"resources/public/js/editor.js\"
        :entries #{\"com.foo.editor\"}
        :depends-on #{:common}}}}


Any namespaces not in an :entries set will be moved into the default
module :cljs-base. However thanks to cross module code motion, Google
Closure can move functions and methods into the modules where they are
actually used. This process is somewhat conservative so if you know
that you want to keep some code together do this via :entries.

The :cljs-base module defaults to being written out to :output-dir
with the name \"cljs_base.js\". This may be overridden by specifying a
:cljs-base module describing only :output-to.

Take careful note that a namespace may only appear once across all
module :entries.

:modules fully supports :foreign-libs. :foreign-libs are always put
into dependency order before any Google Closure compiled source.

Source maps are fully supported, an individual one will be created for
each module. Just supply :source-map true (see example) as there is no
single source map to name.")

;; ** TODO name collision don't want docs to collide
;; this is the only name collision in this
(def-key :cljs.options-schema.modules/output-dir    non-blank-string?)
(def-key ::entries       (s/every ::string-or-symbol :min-count 1 :into [] :kind  (some-fn sequential? set?)))
(def-key ::depends-on    (s/every ::string-or-named :min-count 1 :into [] :kind (some-fn sequential? set?)))

(def-key ::source-map-path            string?

  "Set the path to source files references in source maps to avoid
further web server configuration.

  :source-map-path \"public/js\"")

(def-key ::source-map-asset-path            string?

  "Provides fine grained control over the sourceMappingURL comment that
is appended to generated JavaScript files when source mapping is enabled.
further web server configuration.

  :source-map-asset-path \"http://foo.com/public/js/out\"")

(def-key ::source-map-timestamp       boolean?

  "Add cache busting timestamps to source map urls. This is helpful for
keeping source maps up to date when live reloading code.

  :source-map-timestamp true")

(def-key ::cache-analysis             boolean?

  "Experimental. Cache compiler analysis to disk. This enables faster
cold build and REPL start up times.

For REPLs, defaults to true. Otherwise, defaults to true if and only
if :optimizations is :none.

  :cache-analysis true")

(def-key ::recompile-dependents       boolean?

  "For correctness the ClojureScript compiler now always recompiles
dependent namespaces when a parent namespace changes. This prevents
corrupted builds and swallowed warnings. However this can impact
compile times depending on the structure of the application. This
option defaults to true.

  :recompile-dependents false")

(def-key ::static-fns                 boolean?

  "Employs static dispatch to specific function arities in emitted
JavaScript, as opposed to making use of the call construct. Defaults
to false except under advanced optimizations. Useful to have set to
false at REPL development to facilitate function redefinition, and
useful to set to true for release for performance.

This setting does not apply to the standard library, which is always
compiled with :static-fns implicitly set to true.

  :static-fns true")

;; (def-key ::warnings                   (ref-schema 'CompilerWarnings))

(def-key ::load-tests              boolean?

  "This flag will cause deftest from cljs.test to be ignored if false.

Useful for production if deftest has been used in the production classpath.

Default is true. Has the same effect as binding cljs.analyzer/*load-tests*.

  :load-tests true")

(def-key ::elide-asserts              boolean?

  "This flag will cause all (assert x) calls to be removed during
compilation, including implicit asserts associated with :pre and :post
conditions. Useful for production. Default is always false even in
advanced compilation. Does NOT specify goog.asserts.ENABLE_ASSERTS,
which is different and used by the Closure library.

Note that it is currently not possible to dynamically set *assert* to
false at runtime; this compiler flag must explicitly be used to effect
the elision.

  :elide-asserts true")

(def-key ::pseudo-names               boolean?

  "With :advanced mode optimizations, determines whether readable names
are emitted. This can be useful when debugging issues in the optimized
JavaScript and can aid in finding missing externs. Defaults to false.

  :pseudo-names true")

(def-key ::print-input-delimiter      boolean?

  "Determines whether comments will be output in the JavaScript that can
be used to determine the original source of the compiled code.

Defaults to false.

  :print-input-delimiter false")

(def-key ::output-wrapper             boolean?

  "Wrap the JavaScript output in (function(){...};)() to avoid clobbering
globals. Defaults to false.

  :output-wrapper false")

(def-key ::libs  (s/every string? :min-count 1 :into [] :kind sequential?)

  "Adds dependencies on external js libraries, i.e. Google
Closure-compatible javascript files with correct goog.provides() and
goog.requires() calls. Note that files in these directories will be
watched and a rebuild will occur if they are modified.

Paths or filenames can be given. Relative paths are relative to the
current working directory (usually project root).

Defaults to the empty vector []

  :libs [\"closure/library/third_party/closure\"
         \"src/js\"
         \"src/org/example/example.js\"]")

(def-key ::preamble  (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "Prepends the contents of the given files to each output file. Only
valid with optimizations other than :none.

Defaults to the empty vector []

  :preamble [\"license.js\"]")

(def-key ::hashbang                   boolean?

  "When using :target :nodejs the compiler will emit a shebang as the
first line of the compiled source, making it executable. When your
intention is to build a node.js module, instead of executable, use
this option to remove the shebang.

  :hashbang false")

(def-key ::compiler-stats             boolean?

  "Report basic timing measurements on compiler activity.

Defaults to false.

  :compiler-stats true")

(s/def ::closure-language-in-out-opts
  #{:ecmascript3 :ecmascript5 :ecmascript5-strict
    :ecmascript6 :ecmascript6-typed :ecmascript6-strict
    :no-transpile})

(def-key ::language-in         ::closure-language-in-out-opts

  "Configure the input and output languages for the closure library. May
be :ecmascript3, ecmascript5, ecmascript5-strict, :ecmascript6-typed,
:ecmascript6-strict, :ecmascript6 or :no-transpile.

Defaults to :ecmascript3

  :language-in  :ecmascript3")

(def-key ::language-out        ::closure-language-in-out-opts

  "Configure the input and output languages for the closure library. May
be :ecmascript3, ecmascript5, ecmascript5-strict, :ecmascript6-typed,
:ecmascript6-strict, :ecmascript6 or :no-transpile.

Defaults to :ecmascript3

  :language-out  :ecmascript3")

(def-key ::closure-defines (s/map-of
                            ::string-or-symbol
                            (some-fn number?
                                     string?
                                     boolean?))

  "Set the values of Closure libraries' variables annotated with @define
or with the cljs.core/goog-define helper macro. A common usage is
setting goog.DEBUG to false:

  :closure-defines {\"goog.DEBUG\" false}

or

  :closure-defines {'goog.DEBUG false}

Note when using Lein the quote is unnecessary due to implicit quoting.

For :optimization :none, a :main option must be specified for defines
to work, and only goog-define defines are affected. :closure-defines
currently does not have any effect with :optimization :whitespace.")

(def-key ::npm-deps (s/or :map   (s/map-of ::string-or-named string?)
                          :false false?)
  "Declare NPM dependencies. A map of NPM package names to the desired
versions or the Boolean value false. If false then any existing
node_modules directory will not be indexed nor used. See also
:install-deps.

  :npm-deps {:left-pad \"1.1.3\" }")

(def-key ::install-deps boolean?

  "When set to true, the Clojurescript compiler will handle downloading
the Javascript dependencies defined in the :npm-deps section of the config.

  :install-deps true")

(def-key ::closure-extra-annotations
  (s/every non-blank-string? :min-count 1 :into [] :kind sequential?)

  "Define extra JSDoc annotations that a closure library might use so
that they don't trigger compiler warnings.

  :closure-extra-annotations #{\"api\"}")

(def-key ::anon-fn-naming-policy      #{:off :unmapped :mapped}

  "Strategies for how the Google Closure compiler does naming of
anonymous functions that occur as r-values in assignments and variable
declarations. Defaults to :off.

  :anon-fn-naming-policy :unmapped

The following values are supported:

  :off Don't give anonymous functions names.

  :unmapped Generates names that are based on the left-hand side of
            the assignment. Runs after variable and property renaming,
            so that the generated names will be short and obfuscated.

  :mapped Generates short unique names and provides a mapping from
          them back to a more meaningful name that's based on the
          left-hand side of the assignment.")

(def-key ::optimize-constants         boolean?

  "When set to true, constants, such as keywords and symbols, will only
be created once and will be written to a separate file called
constants_table.js. The compiler will emit a reference to the constant
as defined in the constants table instead of creating a new object for
it. This option is mainly intended to be used for a release build
since it can increase performance due to decreased allocation.
Defaults to true under :advanced optimizations otherwise to false.

  :optimize-constants true")

(def-key ::parallel-build             boolean?

  "When set to true, compile source in parallel, utilizing multiple cores.

:parallel-build true")

(def-key ::devcards                   boolean?

  "Whether to include devcard 'defcard' definitions in the output of the compile.")

(def-key ::watch-fn fn?

  "Is a function that will be called after a successful build.

Only available for cljs.build.api/watch

  :watch-fn (fn [] (println \"Updated build\"))")

(def-key ::process-shim boolean?

  "Defaults to true. Automatically provide a shim for Node.js process.env
containing a single Google Closure define, NODE_ENV with \"development\"
as the default value. In production NODE_ENV will be set to \"production\".
If set to false all of the stated behavior is disabled.

  :process-shim false")

(def-key ::dump-core                  boolean?)
(def-key ::emit-constants             boolean?)
(def-key ::warning-handlers  ;; symbol, string, or fn?
  (s/every ::s/any :min-count 1 :into [] :kind sequential?))
(def-key ::source-map-inline          boolean?)
(def-key ::ups-libs
  (s/every non-blank-string? :min-count 1 :into [] :kind sequential?))
(def-key ::ups-externs
  (s/every non-blank-string? :min-count 1 :into [] :kind sequential?))
(def-key ::ups-foreign-libs
  (s/every ::foreign-libs :min-count 1 :into [] :kind sequential?))
(def-key ::closure-output-charset     non-blank-string?)
(def-key ::external-config            (s/map-of keyword? map?))

(def-key ::fn-invoke-direct boolean?
  "Requires :static-fns true. This option emits slightly different
code that can speed up your code around 10-30%. Higher order
function  that don’t implement the IFn protocol are normally called
with f.call(null, arg0, arg1 …​).

With this option enabled the compiler calls them with a faster
f(arg0, arg1 …​ instead.)

  :fn-invoke-direct true")

(def-key ::rewrite-polyfills boolean?
  "If set to true, the google closure compiler will add polyfills (for example
when you use native javascript Promise). This requires :language-in to be set
to :es6 or higher or it will silently be ignored!

  :language-in :es6
  :rewrite-polyfills true")

(def-key ::aot-cache boolean?
  "A boolean value to disable or enable global caching of compiled assets.

  :aot-cache false")

(def-key ::checked-arrays (s/or :keyval #{:warn :error}
                                :false   false?
                                :nil    nil?)
  "If set to :warn or :error, checks inferred types and runtime values passed
to aget and aset. Inferred type mismatches will result in the
:invalid-array-access warning being triggered. Logs when incorrect values
are passed if set to :warn, throws if set to :error. May be set to a
false-y value to disable this feature.

This setting does not apply if :optimizations is set to :advanced.

  :checked-arrays :warn")

;; ** ClojureScript Compiler Warnings

(def-key ::warnings
  (s/or
   :bool boolean?
   :warnings-map-options
   (strict-keys
    :opt-un
    [::dynamic
     ::extending-base-js-type
     ::extend-type-invalid-method-shape
     ::fn-var
     ::fn-arity
     ::fn-deprecated
     ::invalid-protocol-symbol
     ::invoke-ctor
     ::invalid-arithmetic
     ::invalid-array-access
     ::infer-warning
     ::js-shadowed-by-local
     ::multiple-variadic-overloads
     ::munged-namespace
     ::ns-var-clash
     ::overload-arity
     ::preamble-missing
     ::protocol-deprecated
     ::protocol-invalid-method
     ::protocol-duped-method
     ::protocol-multiple-impls
     ::protocol-with-variadic-method
     ::protocol-impl-with-variadic-method
     ::protocol-impl-recur-with-target
     ::redef
     ::redef-in-file
     ::single-segment-namespace
     ::unprovided
     ::undeclared-var
     ::undeclared-ns
     ::undeclared-ns-form
     ::undeclared-protocol-symbol
     ::unsupported-js-module-type
     ::unsupported-preprocess-value
     ::variadic-max-arity]))

  "This flag will turn on/off compiler warnings for references to
undeclared vars, wrong function call arities, etc. Can be a boolean
for enabling/disabling common warnings, or a map of specific warning
keys with associated booleans. Defaults to true.

  :warnings true

;; OR

  :warnings {:fn-deprecated false} ;; suppress this warning

The following warnings are supported:

  :preamble-missing, missing preamble
  :unprovided, required namespace not provided
  :undeclared-var, undeclared var
  :undeclared-ns, var references non-existent namespace
  :undeclared-ns-form, namespace reference in ns form that does not exist
  :redef, var redefinition
  :dynamic, dynamic binding of non-dynamic var
  :fn-var, var previously bound to fn changed to different type
  :fn-arity, invalid invoke arity
  :fn-deprecated, deprecated function usage
  :protocol-deprecated, deprecated protocol usage
  :undeclared-protocol-symbol, undeclared protocol referred
  :invalid-protocol-symbol, invalid protocol symbol
  :multiple-variadic-overloads, multiple variadic arities
  :variadic-max-arity, arity greater than variadic arity
  :overload-arity, duplicate arities
  :extending-base-js-type, JavaScript base type extension
  :invoke-ctor, type constructor invoked as function
  :invalid-arithmetic, invalid arithmetic
  :protocol-invalid-method, protocol method does not match declaration
  :protocol-duped-method, duplicate protocol method implementation
  :protocol-multiple-impls, protocol implemented multiple times
  :protocol-with-variadic-method, protocol declares variadic signature
  :protocol-impl-with-variadic-method, protocol impl employs variadic signature
  :protocol-impl-recur-with-target, target passed in recur to protocol method head
  :single-segment-namespace, single segment namespace
  :munged-namespace, namespace name contains a reserved JavaScript keyword
  :ns-var-clash, namespace clashes with var
  :extend-type-invalid-method-shape, method arities must be grouped together
  :unsupported-js-module-type, unsupported JavaScript module type
  :unsupported-preprocess-value, unsupported foreign lib preprocess value
  :js-shadowed-by-local, name shadowed by a local
  :infer-warning, warnings related to externs inference")

;; *** TODO differnet ns??

(def-key ::dynamic boolean?)
(def-key ::extending-base-js-type boolean?)
(def-key ::extend-type-invalid-method-shape boolean?)
(def-key ::fn-var boolean?)
(def-key ::fn-arity boolean?)
(def-key ::fn-deprecated boolean?)
(def-key ::invalid-protocol-symbol boolean?)
(def-key ::invoke-ctor boolean?)
(def-key ::invalid-arithmetic boolean?)
(def-key ::invalid-array-access boolean?)
(def-key ::infer-warning boolean?)
(def-key ::js-shadowed-by-local boolean?)
(def-key ::multiple-variadic-overloads boolean?)
(def-key ::munged-namespace boolean?)
(def-key ::ns-var-clash boolean?)
(def-key ::overload-arity boolean?)
(def-key ::preamble-missing boolean?)
(def-key ::protocol-deprecated boolean?)
(def-key ::protocol-invalid-method boolean?)
(def-key ::protocol-duped-method boolean?)
(def-key ::protocol-multiple-impls boolean?)
(def-key ::protocol-with-variadic-method boolean?)
(def-key ::protocol-impl-with-variadic-method boolean?)
(def-key ::protocol-impl-recur-with-target boolean?)
(def-key ::redef boolean?)
(def-key ::redef-in-file boolean?)
(def-key ::single-segment-namespace boolean?)
(def-key ::unprovided boolean?)
(def-key ::undeclared-var boolean?)
(def-key ::undeclared-ns boolean?)
(def-key ::undeclared-ns-form boolean?)
(def-key ::undeclared-protocol-symbol boolean?)
(def-key ::unsupported-js-module-type boolean?)
(def-key ::unsupported-preprocess-value boolean?)
(def-key ::variadic-max-arity boolean?)

;; ** Closure Compiler Warnings

(def-key ::closure-warnings
  (strict-keys
   :opt-un
   [::access-controls
    ::ambiguous-function-decl
    ::analyzer-checks
    ::check-eventful-object-disposal
    ::check-regexp
    ::check-types
    ::check-useless-code
    ::check-variables
    ::closure-dep-method-usage-checks
    ::common-js-module-load
    ::conformance-violations
    ::const
    ::constant-property
    ::debugger-statement-present
    ::deprecated
    ::deprecated-annotations
    ::duplicate-message
    ::duplicate-vars
    ::es3
    ::es5-strict
    ::externs-validation
    ::extra-require
    ::fileoverview-jsdoc
    ::function-params
    ::global-this
    ::inferred-const-checks
    ::internet-explorer-checks
    ::invalid-casts
    ::j2cl-checks
    ::late-provide
    ::lint-checks
    ::message-descriptions
    ::misplaced-type-annotation
    ::missing-getcssname
    ::missing-override
    ::missing-polyfill
    ::missing-properties
    ::missing-provide
    ::missing-require
    ::missing-return
    ::non-standard-jsdoc
    ::report-unknown-types
    ::strict-missing-require
    ::strict-module-dep-check
    ::strict-requires
    ::suspicious-code
    ::tweaks
    ::type-invalidation
    ::undefined-names
    ::undefined-variables
    ::underscore
    ::unknown-defines
    ::unused-local-variable
    ::unused-private-property
    ::use-of-goog-base
    ::violated-module-dep
    ::visiblity])

  "Configure warnings generated by the Closure compiler. A map from
Closure warning to configuration value, only :error, :warning and :off
are supported.

  :closure-warnings {:externs-validation :off}

The following Closure warning options are exposed to ClojureScript:

  :access-controls
  :ambiguous-function-decl
  :analyzer-checks
  :check-eventful-object-disposal
  :check-regexp
  :check-types
  :check-useless-code
  :check-variables
  :closure-dep-method-usage-checks
  :common-js-module-load
  :conformance-violations
  :const
  :constant-property
  :debugger-statement-present
  :deprecated
  :deprecated-annotations
  :duplicate-message
  :duplicate-vars
  :es3
  :es5-strict
  :externs-validation
  :extra-require
  :fileoverview-jsdoc
  :function-params
  :global-this
  :inferred-const-checks
  :internet-explorer-checks
  :invalid-casts
  :j2cl-checks
  :late-provide
  :lint-checks
  :message-descriptions
  :misplaced-type-annotation
  :missing-getcssname
  :missing-override
  :missing-polyfill
  :missing-properties
  :missing-provide
  :missing-require
  :missing-return
  :non-standard-jsdoc
  :report-unknown-types
  :strict-missing-require
  :strict-module-dep-check
  :strict-requires
  :suspicious-code
  :tweaks
  :type-invalidation
  :undefined-names
  :undefined-variables
  :underscore
  :unknown-defines
  :unused-local-variable
  :unused-private-property
  :use-of-goog-base
  :violated-module-dep
  :visiblity

See the Closure Compiler Warning wiki for detailed descriptions.")

(def-key ::warning-value #{:error :warning :off})

;; *** TODO differnet ns??
(def-key  ::access-controls ::warning-value)
(def-key  ::ambiguous-function-decl ::warning-value)
(def-key  ::analyzer-checks ::warning-value)
(def-key  ::check-eventful-object-disposal ::warning-value)
(def-key  ::check-regexp ::warning-value)
(def-key  ::check-types ::warning-value)
(def-key  ::check-useless-code ::warning-value)
(def-key  ::check-variables ::warning-value)
(def-key  ::closure-dep-method-usage-checks ::warning-value)
(def-key  ::common-js-module-load ::warning-value)
(def-key  ::conformance-violations ::warning-value)
(def-key  ::const ::warning-value)
(def-key  ::constant-property ::warning-value)
(def-key  ::debugger-statement-present ::warning-value)
(def-key  ::deprecated ::warning-value)
(def-key  ::deprecated-annotations ::warning-value)
(def-key  ::duplicate-message ::warning-value)
(def-key  ::duplicate-vars ::warning-value)
(def-key  ::es3 ::warning-value)
(def-key  ::es5-strict ::warning-value)
(def-key  ::externs-validation ::warning-value)
(def-key  ::extra-require ::warning-value)
(def-key  ::fileoverview-jsdoc ::warning-value)
(def-key  ::function-params ::warning-value)
(def-key  ::global-this ::warning-value)
(def-key  ::inferred-const-checks ::warning-value)
(def-key  ::internet-explorer-checks ::warning-value)
(def-key  ::invalid-casts ::warning-value)
(def-key  ::j2cl-checks ::warning-value)
(def-key  ::late-provide ::warning-value)
(def-key  ::lint-checks ::warning-value)
(def-key  ::message-descriptions ::warning-value)
(def-key  ::misplaced-type-annotation ::warning-value)
(def-key  ::missing-getcssname ::warning-value)
(def-key  ::missing-override ::warning-value)
(def-key  ::missing-polyfill ::warning-value)
(def-key  ::missing-properties ::warning-value)
(def-key  ::missing-provide ::warning-value)
(def-key  ::missing-require ::warning-value)
(def-key  ::missing-return ::warning-value)
(def-key  ::non-standard-jsdoc ::warning-value)
(def-key  ::report-unknown-types ::warning-value)
(def-key  ::strict-missing-require ::warning-value)
(def-key  ::strict-module-dep-check ::warning-value)
(def-key  ::strict-requires ::warning-value)
(def-key  ::suspicious-code ::warning-value)
(def-key  ::tweaks ::warning-value)
(def-key  ::type-invalidation ::warning-value)
(def-key  ::undefined-names ::warning-value)
(def-key  ::undefined-variables ::warning-value)
(def-key  ::underscore ::warning-value)
(def-key  ::unknown-defines ::warning-value)
(def-key  ::unused-local-variable ::warning-value)
(def-key  ::unused-private-property ::warning-value)
(def-key  ::use-of-goog-base ::warning-value)
(def-key  ::violated-module-dep ::warning-value)
(def-key  ::visiblity ::warning-value)

;; opt none helper
(defn- opt-none? [opt]
  (or (nil? opt) (= :none opt)))

;; ** The Top level Options Map for the cljs/build fn
(def-key ::compiler-options
  (s/and
   map?

   (attach-warning ":asset-path has no effect without a :main or :modules"
                   (fn [{:keys [main modules] :as cmpl}]
                     (not (and (contains? cmpl :asset-path)
                               (nil? main)
                               (nil? modules)))))

   (attach-warning ":pseudo-names has no effect when :optimizations is not :advanced"
                   (fn [{:keys [optimizations] :as cmpl}]
                     (not (and (contains? cmpl :pseudo-names)
                               (not= optimizations :advanced)))))

   ;; **** TODO add in the cljs.compiler unknown/similar-key warning here

   ;; these next warnings probably be elevated to an errors attach-reason
   (attach-warning ":preamble has no effect when :optimizations is :none"
                   (fn [{:keys [optimizations] :as cmpl}]
                     (not (and (contains? cmpl :preamble)
                               (opt-none? optimizations)))))

   (attach-warning ":hashbang has no effect when :target is not :nodejs"
                   (fn [{:keys [target] :as cmpl}]
                     (not (and (contains? cmpl :hashbang) (not= target :nodejs)))))

   (attach-warning ":clojure-defines has no effect when :optimizations is :whitespace"
                   (fn [{:keys [optimizations] :as cmpl}]
                     (not (and (contains? cmpl :closure-defines)
                               (= :whitespace optimizations)))))

   (attach-warning "missing an :output-to option - you probably will want this ..."
                   (fn [cmpl] (or (contains? cmpl :output-to)
                                  (contains? cmpl :modules))))

   (attach-reason  ":closure-defines requires a :main when :optimizations is :none"
                   (fn [{:keys [optimizations main] :as cmpl}]
                     (not (and (contains? cmpl :closure-defines)
                               (nil? main)
                               (opt-none? optimizations))))
                   :focus-path [:closure-defines])

   (attach-reason  ":source-map must be a boolean when :optimizations is :none"
                   (fn [{:keys [source-map optimizations] :as cmpl}]
                     (not (and
                           (contains? cmpl :source-map)
                           (not (boolean? source-map))
                           (opt-none? optimizations))))
                   :focus-path [:source-map])

   (attach-reason  ":source-map must be a string? when :optimizations is not :none"
                   (fn [{:keys [source-map optimizations] :as cmpl}]
                     (not (and
                           (contains? cmpl :source-map)
                           (not (string? source-map))
                           (not (opt-none? optimizations)))))
                   :focus-path [:source-map])


   (strict-keys
    :opt-un
    [::main
     ::preloads
     ::asset-path
     ::output-to
     ::output-dir
     ::closure-warnings
     ::optimizations
     ::source-map
     ::verbose
     ::pretty-print
     ::target
     ::infer-externs
     ::foreign-libs
     ::externs
     ::modules
     ::source-map-path
     ::source-map-asset-path
     ::source-map-timestamp
     ::cache-analysis
     ::recompile-dependents
     ::static-fns
     ::load-tests
     ::elide-asserts
     ::pseudo-names
     ::print-input-delimiter
     ::output-wrapper
     ::libs
     ::preamble
     ::hashbang
     ::compiler-stats
     ::language-in
     ::language-out
     ::npm-deps
     ::install-deps
     ::closure-defines
     ::closure-extra-annotations
     ::anon-fn-naming-policy
     ::optimize-constants
     ::parallel-build
     ::devcards
     ::dump-core
     ::emit-constants
     ::warning-handlers
     ::source-map-inline
     ::ups-libs
     ::ups-externs
     ::ups-foreign-libs
     ::closure-output-charset
     ::external-config
     ::watch-fn
     ::process-shim
     ::warnings
     ::fn-invoke-direct
     ::rewrite-polyfills
     ::checked-arrays
     ::aot-cache

     ;; these need definitions above
     ::closure-variable-map-out
     ::closure-generate-exports
     ::closure-module-roots
     ::rename-prefix
     ::closure-property-map-in
     ::ignore-js-module-exts
     ::closure-property-map-out
     ::stable-names
     ::watch-error-fn
     ::browser-repl
     ::opts-cache
     ::watch
     ::cache-analysis-format
     ::rename-prefix-namespace
     ::closure-variable-map-in
     ::use-only-custom-externs


     ]
    )))
