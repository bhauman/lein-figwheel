#!/bin/bash
clojure -Adev -m figwheel.main -co "{:aot-cache false :asset-path \"out\"}" -w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r
