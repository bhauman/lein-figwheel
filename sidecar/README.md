# Fighweel Sidecar

Figwheel Sidecar is represents the bulk of Figwheel's functionality.

Figwheel Sidecar has been built modulayly to facilitate the creation of build
scripts that match your projects development needs.

If you are wanting to use figwheel **as is** you can use the
`figwheel-sidecar.repl-api`. This api has all the methods you need to
start and control a figwheel process.

The next layer is the `figwheel-sidecar.system` namespace which can
assemble a figwheel configuration into a system of Stuart Sierra's
components. This namespace also provides a set of functions to change
the and query the running system and also the functionality to launch
a ClojureScript REPL provided a system.











