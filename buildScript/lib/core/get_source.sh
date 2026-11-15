#!/usr/bin/env bash
set -euo pipefail

# libcore/go.mod resolves ../../<dependency> to a sibling of this repository.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/buildScript/lib/core/get_source_env.sh"
DEPS="$(dirname "$ROOT")"

checkout_dep() {
  local name="$1" url="$2" commit="$3" dir="$DEPS/$1"
  local -a git_auth_env=()
  if [ -n "${SING_BOX_CLONE_TOKEN:-}" ] && [ "$name" = sing-box ]; then
    # Use an in-memory environment config; never place the token in a clone URL
    # or the repository's origin config, where it could leak into diagnostics.
    git_auth_env=(env GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=http.extraheader "GIT_CONFIG_VALUE_0=Authorization: Bearer ${SING_BOX_CLONE_TOKEN}")
  fi
  git_cmd() { "${git_auth_env[@]}" git "$@"; }
  if [ ! -e "$dir" ]; then
    git_cmd clone "$url" "$dir"
  fi
  if [ ! -d "$dir/.git" ]; then
    echo "Not a Git checkout: $dir" >&2; exit 1
  fi
  if [ -n "$(git -C "$dir" status --porcelain)" ]; then
    echo "Refusing to overwrite dirty dependency: $dir" >&2; exit 1
  fi
  git_cmd -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || git_cmd -C "$dir" fetch "$url" "$commit"
  git -C "$dir" checkout --detach "$commit"
}
checkout_dep sing-box "${SING_BOX_REPO_URL:-https://github.com/starifly/sing-box.git}" "$COMMIT_SING_BOX"
checkout_dep libneko https://github.com/starifly/libneko.git "$COMMIT_LIBNEKO"

for name in sing-anytls-shanlian sing-shadowsocks2-vt fastup-mod oppa-mod; do
  if [ ! -f "$DEPS/$name/go.mod" ]; then
    echo "Missing $DEPS/$name/go.mod; stage the pinned protocol modules as documented in README.md" >&2
    exit 1
  fi
done

# The pinned Nekobox-MG Oppa module predates the sing-box 1.14 TLS interface.
# Patch the staged copy after checkout so CI cannot silently restore the old
# calls and make the gvisor fix appear to be the failing change.
python3 "$ROOT/buildScript/lib/core/patch_protocol_modules.py" "$DEPS/oppa-mod"

# All compatibility changes are committed in the pinned fork. Never rewrite
# its router or platform sources with an older runtime patch snapshot.
test "$(git -C "$DEPS/sing-box" rev-parse HEAD)" = "$COMMIT_SING_BOX"
if [ -n "$(git -C "$DEPS/sing-box" status --porcelain)" ]; then
  echo "Pinned sing-box source must be clean" >&2; exit 1
fi
