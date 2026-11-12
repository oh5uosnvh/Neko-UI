#!/usr/bin/env python3
"""Apply source compatibility fixes to pinned protocol modules.

The protocol modules are staged from Nekobox-MG before get_source.sh runs.  Keep
this patch here so local builds and CI use exactly the same source transform.
"""
from __future__ import annotations

import pathlib
import sys


OPPA_RELATIVE = pathlib.Path("oppa") / "outbound.go"


def replace_expected(path: pathlib.Path, old: str, new: str, expected: int, label: str) -> bool:
    text = path.read_text()
    count = text.count(old)
    if count == 0 and new in text:
        return False
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} {label} source pattern(s), found {count}")
    path.write_text(text.replace(old, new))
    return True


def patch_oppa(root: pathlib.Path) -> int:
    path = root / OPPA_RELATIVE
    if not path.is_file():
        raise SystemExit(f"missing pinned Oppa source: {path}")
    changed = 0
    changed += replace_expected(
        path,
        "tls.NewSTDClient(ctx, options.Server, *options.TLS)",
        "tls.NewSTDClient(ctx, logger, options.Server, *options.TLS)",
        1,
        "sing-box 1.14 NewSTDClient call",
    )
    changed += replace_expected(
        path,
        "tlsConfig.Config()",
        "tlsConfig.STDConfig()",
        2,
        "sing-box 1.14 STDConfig call",
    )
    return changed


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <staged-protocol-module-root>")
    root = pathlib.Path(sys.argv[1]).resolve()
    changed = patch_oppa(root)
    print(f"protocol compatibility patch: {changed} replacement(s) in {root.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
