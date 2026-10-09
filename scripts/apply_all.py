#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Neko-UI 补丁应用器（25 补丁）。在**上游源码树根目录**运行。

用法:
  python3 apply_all.py --check          # 只预检（临时 index 累积干跑，不改工作区）
  python3 apply_all.py                  # 按序应用全部；失败项打印原因并跳过
  python3 apply_all.py --reverse        # 反向应用（倒序回滚）

补丁按文件名序号顺序应用。CONFLICT = 需要按 docs/CHANGES.md 手工移植。

注意：补丁是**链式**的（后面的补丁上下文可能包含前面补丁的改动），
因此预检必须累积执行——旧实现对未打补丁的树逐个 --check，会把 013/014
这类"上下文含 012 改动"的补丁误报为 CONFLICT 并跳过。
"""
import argparse
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
PATCH_DIR = HERE.parent / "patches"

ORDERED = [
    "001-sidebar-cache-prewarm.patch",
    "002-page-scroll-reset.patch",
    "003-groups-page-drag.patch",
    "004-config-page.patch",
    "005-group-settings-autoselect.patch",
    "006-insets-fix.patch",
    "007-tab-strip-official-ui.patch",
    "008-group-item-longpress-only.patch",
    "009-ci-mod-pins-gosumdb.patch",
    "010-group-list-count.patch",
    "011-group-update-progress-stuck.patch",
    "012-connection-test-live-results.patch",
    "013-connection-test-cancel-and-speed.patch",
    "014-delete-unavailable-undo.patch",
    "015-settings-menu-npe-guard.patch",
    "016-preference-edit-unify.patch",
    "017-bottom-connect-bar.patch",
    "018-group-tab-menu-undo.patch",
    "019-connect-bar-polish.patch",
    "020-group-undo-fix.patch",
    "021-connect-bar-fit.patch",
    "022-group-undo-position.patch",
    "023-connect-bar-fit3.patch",
    "024-service-icon-anim.patch",
    "025-ip-quality-multisource.patch",
]


def git(args, cwd=None, env=None):
    return subprocess.run(["git"] + args, capture_output=True, text=True, cwd=cwd, env=env)


def in_worktree() -> bool:
    return git(["rev-parse", "--is-inside-work-tree"]).stdout.strip() == "true"


def check_sequential(names) -> tuple[list, list]:
    """在临时 index 上按序累积应用（不改工作区），返回 (ok, bad)。"""
    ok, bad = [], []
    with tempfile.NamedTemporaryFile(prefix="apply_all_idx_") as tf:
        env = dict(os.environ, GIT_INDEX_FILE=tf.name)
        # 用 HEAD 种子化临时 index（空仓库则 read-tree 失败，直接报错）
        seed = git(["read-tree", "HEAD"], env=env)
        if seed.returncode != 0:
            print("错误：无法用 HEAD 种子化临时 index：", seed.stderr.strip())
            sys.exit(2)
        for name in names:
            r = git(["apply", "--cached", str(PATCH_DIR / name)], env=env)
            (ok if r.returncode == 0 else bad).append(name)
        return ok, bad


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只预检不应用（临时 index 干跑）")
    ap.add_argument("--only-clean", action="store_true",
                    help="兼容保留：顺序应用天然跳过失败项，此开关无额外作用")
    ap.add_argument("--reverse", action="store_true", help="反向应用（倒序回滚）")
    args = ap.parse_args()

    if not in_worktree():
        print("错误：请在上游源码树的 git 工作树根目录运行本脚本。")
        return 2

    names = list(reversed(ORDERED)) if args.reverse else list(ORDERED)
    for n in names:
        if not (PATCH_DIR / n).exists():
            print(f"缺失补丁: {n}")
            sys.exit(2)

    if args.check:
        ok, bad = check_sequential(names)
        print("=== 预检结果（累积干跑）===")
        for n in ok:
            print(f"  CLEAN    {n}")
        for n in bad:
            print(f"  CONFLICT {n}   -> 按 docs/CHANGES.md 对应条目手工移植")
        return 1 if bad else 0

    applied, failed = [], []
    for n in names:
        cmd = ["apply", "-R", str(PATCH_DIR / n)] if args.reverse else \
              ["apply", str(PATCH_DIR / n)]
        r = git(cmd)
        if r.returncode == 0:
            applied.append(n)
            print(f"{'REVERSED' if args.reverse else 'APPLIED '} {n}")
        else:
            failed.append(n)
            print(f"FAILED   {n}\n{(r.stderr or '').strip()}")

    if failed:
        print(f"\n失败 {len(failed)} 个（未应用）: {', '.join(failed)}")
        print("移植指南: docs/REBASE.md 第 3 步；意图与红线: docs/CHANGES.md")
        return 1
    print(f"\n完成（{len(applied)} 个补丁）。下一步: 构建 + scripts/verify_build.py 校验。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
