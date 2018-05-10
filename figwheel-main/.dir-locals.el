((nil
  (eval . (cider-register-cljs-repl-type
           'fm
           "(require 'figwheel.main)(figwheel.main/start \"dev\")"
           'cider-verify-piggieback-is-present
           ))
  (cider-default-cljs-repl . fm)))
