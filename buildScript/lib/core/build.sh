#!/usr/bin/env bash

set -euo pipefail
source "buildScript/init/env.sh"
export PATH="$(go env GOPATH)/bin:$PATH"
export CGO_ENABLED=1
export GO386=softfloat

# A direct build must not accidentally use an unpatched or hand-edited core.
python3 buildScript/lib/core/apply_patches.py "${NEKOBOX_DEPS_DIR:-$(cd .. && pwd)}/sing-box" --check-only

cd libcore
rel=1 ./build.sh || exit 1
