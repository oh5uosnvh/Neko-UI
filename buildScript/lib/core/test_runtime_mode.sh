#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CORE="${NEKOBOX_MODE_CORE:-$(dirname "$ROOT")/sing-box}"
GO="${GO:-go}"
python3 "$ROOT/buildScript/lib/core/apply_patches.py" "$CORE" --check-only
# Run in a temporary module so test commands never alter checked-in module files.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/buildScript/lib/core/harness/"*.go "$TMP/"
cp "$ROOT/buildScript/lib/core/harness/go.mod" "$TMP/"
cp "$ROOT/buildScript/lib/core/harness/go.sum" "$TMP/"
cd "$TMP"
"$GO" mod edit -replace "github.com/sagernet/sing-box=$CORE"
"$GO" test -mod=readonly -count=1 -v ./...
# A race runtime failure is a failure, never silently downgraded to a normal run.
CGO_ENABLED=1 "$GO" test -mod=readonly -race -count=1 -v ./...
