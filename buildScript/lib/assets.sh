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
curl -fLSs -o geoip.db "https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db"
curl -fLSs -o geosite.db "https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db"
printf '%s\n' 'd8f4d22abee199b73c019df267e8dc649e868da3b130753640aa9b05d11040c0  geoip.db' '25ad53c7031b7210379d5c7f327b0a3bd17f74b270ea136c20dfd5ca0f21d226  geosite.db' | sha256sum -c -
printf '%s' "$VERSION_GEOIP" > geoip.version.txt
printf '%s' "$VERSION_GEOSITE" > geosite.version.txt
xz -9 geoip.db geosite.db
cp geoip.db.xz geosite.db.xz geoip.version.txt geosite.version.txt "$DIR/"
