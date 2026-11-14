#!/bin/bash

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

_NDK=""
# Cronet's Android archives are built with NDK r25c. Prefer that exact
# revision even when the runner image exposes a newer default NDK first.
if [ -d "${ANDROID_HOME:-}/ndk" ]; then
  for candidate in "${ANDROID_HOME}/ndk"/*; do
    if [ -f "$candidate/source.properties" ] && rg -q 'Pkg.Revision = 25\.2\.' "$candidate/source.properties"; then
      _NDK="$candidate"
      break
    fi
  done
fi
[ -f "$_NDK/source.properties" ] || _NDK="${ANDROID_NDK_HOME:-}"
[ -f "$_NDK/source.properties" ] || _NDK="${NDK:-}"
[ -f "$_NDK/source.properties" ] || _NDK="${ANDROID_HOME:-}/ndk-bundle"

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found."
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
