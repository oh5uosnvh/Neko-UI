#!/usr/bin/env python3
"""Static Android netlink guard, complementary to production-bridge runtime tests.

This does not prove device connectivity. Go's Android net.Interface* helpers
use NETLINK_ROUTE, forbidden to unprivileged apps on modern Android. Physical
interface snapshots must come through the Android/JNI platform provider instead.
Host-only *_test.go fixtures may deliberately enumerate their host interfaces.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors = []
for relative in ("libcore/platform_box.go", "libcore/interface_monitor.go"):
    source = (ROOT / relative).read_text()
    # Ignore comments, not string values: catch production API references too,
    # e.g. assigning net.InterfaceByName as a function-valued lookup callback.
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    forbidden = re.findall(r"\bnet\.(?:Interfaces|InterfaceByName|InterfaceByIndex|InterfaceAddrs)\b", source)
    if forbidden:
        errors.append(f"{relative}: forbidden Android netlink helpers: {', '.join(forbidden)}")
    if re.search(r"\b(?:iif|interface_)\.Addrs\s*\(", source):
        errors.append(f"{relative}: interface addresses must come from LinkProperties, not Go .Addrs()")
platform = (ROOT / "libcore/platform_box.go").read_text()
if not re.search(r"UsePlatformNetworkInterfaces\(\)\s*bool\s*\{\s*return true\s*\}", platform):
    errors.append("Android production provider must opt in to UsePlatformNetworkInterfaces")
box = (ROOT / "libcore/box.go").read_text()
if "service.MustRegister[adapter.PlatformInterface]" not in box:
    errors.append("production Box must register the adapter.PlatformInterface service key")
if errors:
    for error in errors:
        print("FAIL:", error, file=sys.stderr)
    sys.exit(1)
print("Android production network bridge netlink source guard passed; device acceptance is separate")
