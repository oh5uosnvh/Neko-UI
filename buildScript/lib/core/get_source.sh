#!/usr/bin/env bash
set -euo pipefail

# libcore/go.mod resolves ../../<dependency> to a sibling of this repository.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/buildScript/lib/core/get_source_env.sh"
DEPS="$(dirname "$ROOT")"

checkout_dep() {
  local name="$1" url="$2" commit="$3" dir="$DEPS/$1"
  if [ ! -e "$dir" ]; then
    python3 "$ROOT/buildScript/lib/core/github_git.py" clone "$url" "$dir"
  fi
  if [ ! -d "$dir/.git" ]; then
    echo "Not a Git checkout: $dir" >&2; exit 1
  fi
  if [ -n "$(git -C "$dir" status --porcelain)" ]; then
    echo "Refusing to overwrite dirty dependency: $dir" >&2; exit 1
  fi
  git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || python3 "$ROOT/buildScript/lib/core/github_git.py" -C "$dir" fetch origin "$commit"
  git -C "$dir" checkout --detach "$commit"
}
checkout_dep sing-box "$SING_BOX_REPO_URL" "$COMMIT_SING_BOX"
if ! git -C "$DEPS/sing-box" merge-base --is-ancestor "$SING_BOX_UPSTREAM_COMMIT" HEAD; then
  echo "Core does not contain the real v1.15.0-alpha.13 upstream commit" >&2; exit 1
fi
checkout_dep libneko https://github.com/starifly/libneko.git "$COMMIT_LIBNEKO"
# Retain pre26 Snell v1-v6/QUIC wire independently of the modern upstream
# library used by URLTest. Ignore only our own idempotent namespace edits.
SNELL_DIR="$DEPS/sing-snell-pre26"
if [ ! -e "$SNELL_DIR" ]; then
  git clone https://github.com/reF1nd/sing-snell.git "$SNELL_DIR"
fi
if [ "$(git -C "$SNELL_DIR" rev-parse HEAD)" != "$COMMIT_SNELL_PRE26" ]; then
  if [ -n "$(git -C "$SNELL_DIR" status --porcelain)" ]; then
    echo "Refusing to overwrite dirty Snell dependency" >&2; exit 1
  fi
  git -C "$SNELL_DIR" cat-file -e "$COMMIT_SNELL_PRE26^{commit}" 2>/dev/null || git -C "$SNELL_DIR" fetch origin "$COMMIT_SNELL_PRE26"
  git -C "$SNELL_DIR" checkout --detach "$COMMIT_SNELL_PRE26"
fi

for name in sing-anytls-shanlian sing-shadowsocks2-vt fastup-mod oppa-mod; do
  if [ ! -f "$DEPS/$name/go.mod" ]; then
    echo "Missing $DEPS/$name/go.mod; stage the pinned protocol modules as documented in README.md" >&2
    exit 1
  fi
done
python3 "$ROOT/buildScript/lib/core/stage_compat.py"
