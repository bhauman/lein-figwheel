#!/bin/bash

set -ex

# We install all JARs so that cross project dependencies work correctly.
for project in plugin sidecar support; do
  pushd ${project}
  lein install
  popd
done
