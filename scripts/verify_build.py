#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APK 成品校验（标准库实现，无第三方依赖）。

用法: python3 verify_build.py <apk路径>
校验:
  1) dex 内功能锚点 + libgojni 协议 mod 标记（release 包同样适用）
  2) dex 内 UI 保留符号（8 项功能规格的锚点）
  3) dex 内不得出现历史废案符号 + 已移除功能符号（☷ 手柄等）
  4) libgojni.so 协议 mod 标记（x365/viewTurbo/fastup/oppa-mod/mihomo 伪装）
"""
import sys
import zipfile

REQUIRED_DEX = [
    b"openGroupAt", b"scrollToGroup", b"resetScrollState", b"calculateDiff",
    b"applyFirstFill", b"syncOrderFromDb",
    b"drawerWarmRunnable", b"applyGroupCounts", b"armFallback",
    b"ConnectBar", b"OutboundIpDialogFragment", b"deleteGroupWithUndo",
    b"bindCopy", b"restoreGroupAt", b"ServiceIconView", b"IpQualityLookup",
]
FORBIDDEN_DEX = [
    b"prefetchJob", b"prefetchGroupPages", b"pageWarmStep", b"pageWarmCap",
    b"warmUpJob", b"isPagerBusy", b"pendingStateRefreshes",
    b"sharedRecycledViewPool", b"ensureProfilesLoaded", b"startPageWarmUp",
    b"sortButton", b"groupSort",
]
REQUIRED_SO = [b"x365", b"viewTurbo", b"fastup", b"oppa-mod/oppa", b"mihomo/1.19.25"]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    z = zipfile.ZipFile(apk)
    ok = True

    dexes = [z.read(n) for n in z.namelist() if n.endswith(".dex")]
    blob = b"".join(dexes)
    for m in REQUIRED_DEX:
        hit = m in blob
        print(f"[{'OK ' if hit else 'MISS'}] dex 符号 {m.decode()}")
        ok &= hit
    for m in FORBIDDEN_DEX:
        hit = m in blob
        print(f"[{'BAD' if hit else 'OK '}] 废案符号 {m.decode()}")
        ok &= not hit

    so = b""
    for n in z.namelist():
        if n.endswith("libgojni.so"):
            so = z.read(n)
            break
    if not so:
        print("[MISS] libgojni.so 不存在")
        return 1
    for m in REQUIRED_SO:
        hit = m in so
        print(f"[{'OK ' if hit else 'MISS'}] libgojni 标记 {m.decode()}")
        ok &= hit

    print("\n结论:", "PASS — 无瑕疵成品" if ok else "FAIL — 见上方 BAD/MISS 项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
