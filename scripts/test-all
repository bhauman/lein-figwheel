#!/bin/bash

set -ex

# TODO: add  "figwheel-main"
for project in plugin sidecar support; do
  pushd ${project}
  lein test
  popd
done
