#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A02 补丁应用器。在**上游源码树根目录**运行。

用法:
  python3 apply_all.py --check          # 只预检，报告每个补丁 CLEAN/CONFLICT
  python3 apply_all.py                  # 按序应用全部可应用补丁
  python3 apply_all.py --only-clean     # 跳过 CONFLICT 的，应用其余
  python3 apply_all.py --reverse        # 反向应用（回滚）

补丁按文件名序号顺序应用。CONFLICT = 需要按 docs/CHANGES.md 手工移植。
"""
import argparse
import pathlib
import subprocess
import sys

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
]


def git(args, cwd=None):
    return subprocess.run(["git"] + args, capture_output=True, text=True, cwd=cwd)


def in_worktree() -> bool:
    return git(["rev-parse", "--is-inside-work-tree"]).stdout.strip() == "true"


def try_apply(patch: pathlib.Path, reverse: bool) -> bool:
    args = ["apply", "--check"]
    if reverse:
        args.append("-R")
    args.append(str(patch))
    return git(args).returncode == 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只预检不应用")
    ap.add_argument("--only-clean", action="store_true", help="跳过会冲突的补丁")
    ap.add_argument("--reverse", action="store_true", help="反向应用（回滚）")
    args = ap.parse_args()

    if not in_worktree():
        print("错误：请在上游源码树的 git 工作树根目录运行本脚本。")
        return 2

    clean, conflict = [], []
    for name in ORDERED:
        p = PATCH_DIR / name
        if not p.exists():
            print(f"缺失补丁: {name}")
            sys.exit(2)
        (clean if try_apply(p, args.reverse) else conflict).append(name)

    print("=== 预检结果 ===")
    for n in clean:
        print(f"  CLEAN   {n}")
    for n in conflict:
        print(f"  CONFLICT {n}   -> 按 docs/CHANGES.md 对应条目手工移植")

    if args.check:
        return 0

    failed = []
    for n in clean:
        r = git(["apply", "-R", str(PATCH_DIR / n)] if args.reverse else ["apply", str(PATCH_DIR / n)])
        if r.returncode == 0:
            print(f"APPLIED  {n}")
        else:
            print(f"FAILED   {n}\n{r.stderr}")
            failed.append(n)

    if args.only_clean:
        print(f"\n已按 --only-clean 跳过 CONFLICT: {', '.join(conflict) or '无'}")
    elif conflict:
        print(f"\n存在 CONFLICT（未应用）: {', '.join(conflict)}")
        print("移植指南: docs/REBASE.md 第 3 步；意图与红线: docs/CHANGES.md")

    if failed:
        print(f"\n应用失败: {failed}")
        return 1
    print("\n完成。下一步: 构建 + scripts/verify_build.py 校验。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
