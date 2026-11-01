#!/usr/bin/env bash

set -euo pipefail
source "buildScript/init/env.sh"
export PATH="$(go env GOPATH)/bin:$PATH"
export CGO_ENABLED=1
export GO386=softfloat

cd libcore
rel=1 ./build.sh || exit 1
