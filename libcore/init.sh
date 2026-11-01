#!/usr/bin/env bash
set -euo pipefail

# Build a known gomobile revision in an isolated temporary checkout. Never trust
# an existing unversioned executable from GOPATH/bin.
GOMOBILE_COMMIT=17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f
GOPATH="${GOPATH:-$(go env GOPATH)}"
export PATH="$GOPATH/bin:$PATH"
mkdir -p "$GOPATH/bin"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet https://github.com/MatsuriDayo/gomobile.git "$tmp/gomobile"
git -C "$tmp/gomobile" checkout --quiet --detach "$GOMOBILE_COMMIT"
(cd "$tmp/gomobile" && go build -trimpath -o "$GOPATH/bin/gomobile-matsuri" ./cmd/gomobile && go build -trimpath -o "$GOPATH/bin/gobind-matsuri" ./cmd/gobind)
# Do not run gomobile init: this revision installs gobind@latest, which
# introduces an unpinned tool and is unnecessary for Android bind without OpenAL.
