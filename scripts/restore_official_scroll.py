#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把节点列表的滚动条恢复成官方实现。

在**已打完 UI 补丁的上游源码树根目录**运行：

  python3 scripts/restore_official_scroll.py ../upstream

背景：补丁 032/033 用 ExactScrollRecyclerView 接管了右侧主题色滑块的
触摸、抓取偏移与命中范围，导致

  * 滑块可拖动的上限/下限与官方不一致（拖不到底，看起来少一个卡片的高度）；
  * 触摸命中范围被自定义放大，和官方手势不一致。

这里恢复官方行为：列表改用官方的 FastScrollRecyclerView，自定义类退化为
纯继承、不再拦截任何触摸事件。
"""

from __future__ import annotations

import pathlib
import sys

FASTSCROLL = "com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView"
CUSTOM = "io.nekohasekai.sagernet.widget.ExactScrollRecyclerView"

LAYOUTS = [
    "app/src/main/res/layout/layout_profile_list.xml",
    "app/src/main/res/layout/layout_route.xml",
]

PASSTHROUGH = '''package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView

/**
 * Kept as a name only: the list uses FastScrollRecyclerView's own scrollbar.
 *
 * The previous implementation re-implemented dragging (grabOffset,
 * trackingTouch, getScrollBarThumbHeight overrides), which changed the thumb's
 * travel limits and hit area. Official behaviour is restored by inheriting and
 * overriding nothing.
 */
class ExactScrollRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FastScrollRecyclerView(context, attrs, defStyleAttr)
'''


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
    changed = False

    for rel in LAYOUTS:
        path = root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if CUSTOM in text:
            path.write_text(text.replace(CUSTOM, FASTSCROLL), encoding="utf-8")
            changed = True

    widget = root / "app/src/main/java/io/nekohasekai/sagernet/widget/ExactScrollRecyclerView.kt"
    if widget.is_file():
        widget.write_text(PASSTHROUGH, encoding="utf-8")
        changed = True

    if not changed:
        print("[official-scroll] nothing to restore (already official)")
        return 0

    for rel in LAYOUTS:
        text = (root / rel).read_text(encoding="utf-8") if (root / rel).is_file() else ""
        assert CUSTOM not in text, f"{rel} still references the custom view"
    assert "override fun dispatchTouchEvent" not in widget.read_text(encoding="utf-8")
    print("[official-scroll] restored FastScrollRecyclerView thumb behaviour")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())