#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIR="$ROOT/app/src/main/assets/sing-box"
mkdir -p "$DIR"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
VERSION_GEOIP=20260812
VERSION_GEOSITE=20261030132534
download_asset() {
    local output="$1" primary="$2" asset_id="$3" repo="$4"
    # GitHub release web endpoints sometimes persistently return 504 while the
    # official asset API serves the SAME pinned blob. Hashes below remain gates.
    if ! curl -fLSs --connect-timeout 20 --max-time 120 --retry 2 --retry-delay 2 -o "$output" "$primary"; then
        echo "Release endpoint unavailable; trying official asset API for $output" >&2
        curl -fLSs --connect-timeout 20 --max-time 120 --retry 3 --retry-delay 2 \
            -H 'Accept: application/octet-stream' -o "$output" \
            "https://api.github.com/repos/SagerNet/$repo/releases/assets/$asset_id"
    fi
}
download_asset geoip.db "https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db" 511349217 sing-geoip
download_asset geosite.db "https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db" 694393112 sing-geosite
printf '%s\n' 'd8f4d22abee199b73c019df267e8dc649e868da3b130753640aa9b05d11040c0  geoip.db' '25ad53c7031b7210379d5c7f327b0a3bd17f74b270ea136c20dfd5ca0f21d226  geosite.db' | sha256sum -c -
printf '%s' "$VERSION_GEOIP" > geoip.version.txt
printf '%s' "$VERSION_GEOSITE" > geosite.version.txt
xz -9 geoip.db geosite.db
cp geoip.db.xz geosite.db.xz geoip.version.txt geosite.version.txt "$DIR/"
