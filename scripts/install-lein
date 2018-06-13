#!/bin/bash

set -ex

# Used for CircleCI so that we can have a build matrix of multiple JDK's to test against.
# The Clojure images they provide only have a single JDK 8 available.
sudo wget -O /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
sudo chmod a+x /usr/local/bin/lein
