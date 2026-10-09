#!/usr/bin/env bash
# Neko-UI APK 成品断言器：libgojni 协议 mod 标记 + verify_build.py（dex 锚点/废案符号）。
#
# 双架构通用：解包 APK 内全部 lib/*/libgojni.so 逐个校验
# （split 架构下每个 APK 各含一个 ABI；未来出 universal 包也能覆盖）。
#
# 用法:
#   bash scripts/assert_apk.sh <apk路径>
set -euo pipefail

APK="${1:?用法: assert_apk.sh <apk路径>}"
[ -f "$APK" ] || { echo "::error::APK 不存在: $APK"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 无匹配时 unzip 返回非零——后面统一检查 found，不在这里中断
unzip -o -q "$APK" 'lib/*/libgojni.so' -d "$TMP" || true

found=0
for SO in "$TMP"/lib/*/libgojni.so; do
  [ -f "$SO" ] || continue
  found=1
  abi="$(basename "$(dirname "$SO")")"
  check() {
    n=$(strings -a "$SO" | grep -c "$2" || true)
    echo "  [$abi] $1: $n"
    [ "$n" -ge "$3" ] || { echo "::error::$1 (\"$2\") missing in $abi"; exit 1; }
  }
  check x365       x365 1
  check efan-UA    'Chrome/120.0.0.0' 1
  check Blackstone 'do not hack this protocol please' 1
  check viewTurbo  viewTurbo 1
  check fastup     fastup 1
  check oppa       'oppa-mod/oppa' 1
  check mihomo     'mihomo/1.19.25' 1
  echo "libgojni [$abi] 协议标记齐全"
done

[ "$found" = 1 ] || { echo "::error::libgojni.so 不存在于 APK"; exit 1; }

python3 "$SCRIPT_DIR/verify_build.py" "$APK"
