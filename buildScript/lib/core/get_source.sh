#!/usr/bin/env bash
set -euo pipefail

# libcore/go.mod resolves ../../<dependency> to a sibling of this repository.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/buildScript/lib/core/get_source_env.sh"
DEPS="${NEKOBOX_DEPS_DIR:-$(dirname "$ROOT")}"

checkout_dep() {
  local name="$1" url="$2" commit="$3" dir="$DEPS/$1"
  if [ ! -e "$dir" ]; then
    git clone "$url" "$dir"
  fi
  if [ ! -d "$dir/.git" ]; then
    echo "Not a Git checkout: $dir" >&2; exit 1
  fi
  # A previously applied checked-in core series is expected to be dirty.
  # Verify it exactly instead of checking out over it. Unknown changes fail.
  if [ "$name" = sing-box ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ]; then
    python3 "$ROOT/buildScript/lib/core/apply_patches.py" "$dir"
    return
  fi
  if [ -n "$(git -C "$dir" status --porcelain --untracked-files=all)" ] || [ -n "$(git -C "$dir" ls-files --others)" ]; then
    echo "Refusing to overwrite dirty dependency: $dir" >&2; exit 1
  fi
  git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$dir" fetch origin "$commit"
  git -C "$dir" checkout --detach "$commit"
  if [ "$name" = sing-box ]; then
    python3 "$ROOT/buildScript/lib/core/apply_patches.py" "$dir"
  fi
}
checkout_dep sing-box https://github.com/starifly/sing-box.git "$COMMIT_SING_BOX"
checkout_dep libneko https://github.com/starifly/libneko.git "$COMMIT_LIBNEKO"

for name in sing-anytls-shanlian sing-shadowsocks2-vt fastup-mod oppa-mod; do
  if [ ! -f "$DEPS/$name/go.mod" ]; then
    echo "Missing $DEPS/$name/go.mod; stage the pinned protocol modules as documented in README.md" >&2
    exit 1
  fi
done
