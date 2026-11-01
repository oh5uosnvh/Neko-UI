#!/bin/bash
# Build the hev-socks5-tunnel JNI library (libhev-socks5-tunnel.so) for all
# Android ABIs and place the artifacts under app/src/main/jniLibs.
#
# Source: the hev-socks5-tunnel git submodule at the repository root
# (initialized automatically when missing). The app Gradle build invokes this
# script via the "buildHevTun" task whenever the .so artifacts are absent.
#
# The JNI entry points (TProxyStartService/TProxyStopService/TProxyIsRunning/
# TProxyGetStats) are bound at library load time via RegisterNatives to the
# class named by -DPKGNAME/-DCLSNAME below; keep them in sync with
# moe.matsuri.nb4a.hevtun.HevTunNative.
#
# Usage:
#   ./buildScript/compile-hevtun.sh
#
# Optional environment overrides:
#   HEV_SRC     path to a hev-socks5-tunnel checkout
#               (default: <repo root>/hev-socks5-tunnel submodule)
# An override must be an already initialized source checkout.
set -o errexit
set -o pipefail
set -o nounset

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/init/env_ndk.sh"

HEV_SRC="${HEV_SRC:-$REPO_DIR/hev-socks5-tunnel}"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"
OUT_DIR="$REPO_DIR/app/src/main/jniLibs"
BUILD_DIR="$REPO_DIR/.hev-build"

if [ ! -f "$HEV_SRC/Android.mk" ]; then
    if [ "$HEV_SRC" = "$REPO_DIR/hev-socks5-tunnel" ] && git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1; then
        git -C "$REPO_DIR" submodule update --init --recursive hev-socks5-tunnel
    fi
fi
if [ ! -f "$HEV_SRC/Android.mk" ] || [ ! -f "$HEV_SRC/src/core/include/hev-socks5-server.h" ]; then
    echo "Missing initialized hev source at $HEV_SRC (including nested submodules)" >&2
    exit 1
fi
HEV_SRC="$(cd "$HEV_SRC" && pwd)"

# Avoid stale objects when the source, flags or toolchain change.
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
pushd "$BUILD_DIR" > /dev/null

mkdir -p jni
ln -sfn "$HEV_SRC" jni/hev-socks5-tunnel
echo 'include $(call all-subdir-makefiles)' > jni/Android.mk

"$ANDROID_NDK_HOME/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
    "APP_ABI=$ABIS" \
    APP_PLATFORM=android-21 \
    "APP_CFLAGS=-O3 -DPKGNAME=moe/matsuri/nb4a/hevtun -DCLSNAME=HevTunNative" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" \
    NDK_LIBS_OUT="$BUILD_DIR/libs" \
    NDK_OUT="$BUILD_DIR/obj"

popd > /dev/null

for abi in $ABIS; do
    mkdir -p "$OUT_DIR/$abi"
    cp -f "$BUILD_DIR/libs/$abi/libhev-socks5-tunnel.so" "$OUT_DIR/$abi/"
    echo ">> install $OUT_DIR/$abi/libhev-socks5-tunnel.so"
done
