#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
source ../buildScript/lib/core/get_source_env.sh
actual_core=$(git -C ../../sing-box rev-parse HEAD)
[ "$actual_core" = "$COMMIT_SING_BOX" ] || { echo 'Core checkout differs from pinned source' >&2; exit 1; }
git -C ../../sing-box merge-base --is-ancestor "$SING_BOX_UPSTREAM_COMMIT" HEAD || exit 1
core_version="1.15.0-alpha.13-neko-${actual_core:0:12}"
ldflags="-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=$core_version"
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="$ldflags" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
