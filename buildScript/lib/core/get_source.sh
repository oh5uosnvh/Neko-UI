#!/usr/bin/env bash
set -euo pipefail

# libcore/go.mod resolves ../../<dependency> to a sibling of this repository.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/buildScript/lib/core/get_source_env.sh"
DEPS="$(dirname "$ROOT")"

checkout_dep() {
  local name="$1" url="$2" commit="$3" dir="$DEPS/$1"
  if [ ! -e "$dir" ]; then
    git clone "$url" "$dir"
  fi
  if [ ! -d "$dir/.git" ]; then
    echo "Not a Git checkout: $dir" >&2; exit 1
  fi
  if [ -n "$(git -C "$dir" status --porcelain)" ]; then
    if [ "$name" = sing-box ] && [ -f "$dir/.git/neko-runtime-routing.json" ]; then
      # Runtime-patched tree. If the pinned commit moved, reset to the new base
      # (the patch is baked into the 1.14.x-neko branch commits) and re-apply.
      manifest_commit=$(python3 -c "import json;print(json.load(open('$dir/.git/neko-runtime-routing.json'))['commit'])" 2>/dev/null || echo "")
      if [ "$manifest_commit" != "$commit" ]; then
        git -C "$dir" checkout -- . || true
        git -C "$dir" clean -fd || true
        git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$dir" fetch origin "$commit"
        git -C "$dir" checkout --detach "$commit"
      else
        python3 "$ROOT/libcore/runtimepatch/apply.py" --verify-existing "$dir" "$commit"
        return
      fi
    else
      echo "Refusing to overwrite dirty dependency: $dir" >&2; exit 1
    fi
  fi
  git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$dir" fetch origin "$commit"
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

# Extend the pinned router with a tested, atomic route+DNS snapshot API.
python3 "$ROOT/libcore/runtimepatch/apply.py" "$DEPS/sing-box"
