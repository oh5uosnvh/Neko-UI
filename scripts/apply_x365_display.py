#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VLESS + uuid 末尾 #x365 的节点，列表里仍显示 VLESS。

出站已经由 apply_final_protocols.py 改成 VLESS（libcore 按后缀走 x365 握手），
但 ProxyEntity.displayType() 仍然把 isX365() 的节点标成 "x365"。这里只改显示：
仅遗留的 alterId == -2（真正的 x365 节点）继续显示 x365。
"""
from __future__ import annotations

import pathlib
import sys

REL = "app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt"
OLD = '            vmessBean!!.isX365() -> "x365"'
NEW = '            vmessBean!!.alterId == -2 -> "x365"'


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
    path = root / REL
    if not path.is_file():
        raise SystemExit(f"[x365-display] 找不到 {path}")
    text = path.read_text(encoding="utf-8")
    if NEW in text:
        print("[x365-display] already applied")
        return 0
    if OLD not in text:
        raise SystemExit("[x365-display] 锚点缺失（displayType 已变化，请人工确认）")
    path.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    print("[x365-display] VLESS #x365 keeps the VLESS label")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
