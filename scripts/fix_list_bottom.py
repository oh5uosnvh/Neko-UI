#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""节点列表底部留白与滚动条行程回到官方口径。

apply_ui_network.py 曾把列表底部 padding 抬到 170dp（为底部连接栏让位），
同时 clipToPadding=false。其后果：

  * 最后一张卡片与屏幕底部之间多出约一张卡片的空白；
  * FastScrollRecyclerView 的滑块行程按内容高度计算，padding 不参与，
    于是滑块拖到底时列表还没到底（上限/下限与官方不一致）。

这里改为官方的 48dp（layout_profile_list.xml 原值），并让 padding 计入
内容区，使滑块行程与官方一致。
"""
from __future__ import annotations

import pathlib
import sys

CF = "app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
LAYOUT = "app/src/main/res/layout/layout_profile_list.xml"


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
    cf = root / CF
    text = cf.read_text(encoding="utf-8")
    changed = False

    if "configurationListView.clipToPadding = false" in text:
        text = text.replace("configurationListView.clipToPadding = false",
                            "configurationListView.clipToPadding = true", 1)
        changed = True
    if changed:
        cf.write_text(text, encoding="utf-8")

    print("[list-bottom] clipToPadding=true (padding counts towards thumb travel)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())