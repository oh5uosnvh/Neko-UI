#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""托板整体还原为 starifly 官方源码。

A01 基线 f167ebc 已核实是 starifly/NekoBoxForAndroid 的官方提交。
017-027/030/033 号补丁删除了官方托板（StatsBar/ServiceButton/FabProgressBehavior），
换成了自定义 ConnectBar 长条启动栏。本脚本在补丁链全部应用后运行，
把启动按钮点击后的托板逐字节恢复为官方实现：

  1) layout_main.xml                → 官方 ServiceButton(FAB) + StatsBar(tx/rx/status)
  2) StatsBar.kt / ServiceButton.kt / FabProgressBehavior.kt → 官方文件（补丁曾删除）
  3) MainActivity.kt                → 官方 + 001侧边栏缓存 + 订阅导入选中分组 + urlTest冷启动重试
  4) ConfigurationFragment.kt       → 官方 StatsBar 滚动联动（setupBottomBarScrollDriver）
  5) Constants/DataStore/global_preferences/strings → 官方 showBottomBar 键
  6) 删除全部定制托板（ConnectBar/ServiceIconView/layout_connect_bar/bar_delay_*）

末尾自带硬断言：任何定制托板残留 = 构建失败。
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

BASE = "f167ebc88e9a733e65380e13dab39726eba917b2"
tree = Path(sys.argv[1]).resolve()
HERE = Path(__file__).resolve().parent
KIT = HERE.parent
J = "app/src/main/java/io/nekohasekai/sagernet"
R = "app/src/main/res"


def git(*args):
    r = subprocess.run(["git", "-C", str(tree)] + list(args),
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed:\n{r.stderr}")
    return r.stdout


def official(path):
    return git("show", f"{BASE}:{path}")


def read(path):
    return (tree / path).read_text()


def write(path, content):
    f = tree / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(content)


def apply_patch_section(patch_name, rel_path, reverse=False):
    """从 kit 补丁文件中抽出指定文件的段落在源码树上 (反向) 应用。"""
    patch = (KIT / "patches" / patch_name).read_text()
    sections = patch.split("diff --git ")
    sec = [s for s in sections if s.startswith("a/" + rel_path)]
    if not sec:
        raise SystemExit(f"{patch_name}: 找不到 {rel_path} 段落")
    with tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False) as tf:
        tf.write("diff --git " + sec[0])
        tmp = tf.name
    cmd = ["git", "-C", str(tree), "apply"]
    if reverse:
        cmd.append("--reverse")
    cmd.append(tmp)
    r = subprocess.run(cmd, capture_output=True, text=True)
    Path(tmp).unlink()
    if r.returncode != 0:
        raise SystemExit(
            f"{patch_name} [{'reverse' if reverse else 'apply'}] {rel_path} 失败:\n{r.stderr}{r.stdout}"
        )


print("== 1) 官方托板文件逐字节还原 ==")
official_files = [
    f"{J}/widget/StatsBar.kt",
    f"{J}/widget/ServiceButton.kt",
    f"{J}/widget/FabProgressBehavior.kt",
    f"{R}/layout/layout_main.xml",
    f"{R}/xml/global_preferences.xml",
]
for p in official_files:
    write(p, official(p))
    print(f"  restored {p}")

print("== 2) 删除定制托板文件 ==")
for p in [
    f"{J}/widget/ConnectBar.kt",
    f"{J}/widget/ServiceIconView.kt",
    f"{R}/layout/layout_connect_bar.xml",
    f"{R}/drawable/bg_touch_rounded.xml",
]:
    f = tree / p
    if f.exists():
        f.unlink()
        print(f"  deleted {p}")

print("== 3) MainActivity = 官方 + 001侧边栏缓存 + 保留修复 ==")
MA = f"{J}/ui/MainActivity.kt"
write(MA, official(MA))
apply_patch_section("001-sidebar-cache-prewarm.patch", MA)
ma = read(MA)

old_sub = """    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }"""
new_sub = """    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val created = GroupManager.createGroup(subscription)
        DataStore.selectedGroup = created.id
        DataStore.editingGroup = created.id
        GroupUpdater.startUpdate(created, true)
    }"""
assert old_sub in ma, "官方 finishImportSubscription 形状变化，需人工核对"
ma = ma.replace(old_sub, new_sub, 1)

old_test = "        return connection.service!!.urlTest()"
new_test = """        // 魔改协议（x365 等）冷启动首测可能失败：延迟 700ms 重试一次
        return try {
            connection.service!!.urlTest()
        } catch (first: Exception) {
            Logs.w("urlTest cold-start retry: ${first.message}")
            Thread.sleep(700)
            if (!DataStore.serviceState.connected || connection.service == null) {
                throw first
            }
            connection.service!!.urlTest()
        }"""
assert old_test in ma, "官方 urlTest 形状变化，需人工核对"
ma = ma.replace(old_test, new_test, 1)
write(MA, ma)
print("  MainActivity: 官方托板接线 (fab/stats/syncMainControls/driveBottomBar) + 001 + 2 保留修复")

print("== 4) ConfigurationFragment: 官方 StatsBar 滚动联动 ==")
CF = f"{J}/ui/ConfigurationFragment.kt"
cur = read(CF)
off = official(CF)
mark = "        private fun setupBottomBarScrollDriver()"
i_call = off.rindex("                setupBottomBarScrollDriver()", 0, off.index(mark))
j_end = off.index("        override fun onDestroy()")
official_seg = off[i_call:j_end]

a17 = cur.index("            // 017: 底部常驻启动栏")
j17 = cur.index("        override fun onDestroy()", a17)
anchor = "                setupItemTouchHelper()\n"
i_cur = cur.index(anchor) + len(anchor)
assert cur.index(anchor) < a17, "017 区块锚点异常"
cur = cur[:i_cur] + official_seg + cur[j17:]

for imp in [
    "import androidx.core.view.ViewCompat\n",
    "import androidx.core.view.WindowInsetsCompat\n",
    "import androidx.core.view.updatePadding\n",
]:
    assert imp in cur, f"缺少 017 注入的 import: {imp.strip()}"
    cur = cur.replace(imp, "", 1)
write(CF, cur)
print("  ConfigurationFragment: setupBottomBarScrollDriver 还原")

print("== 5) 官方 showBottomBar 键还原 ==")
CONST = f"{J}/Constants.kt"
c = read(CONST)
old_c = '    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"\n'
assert old_c in c and "SHOW_BOTTOM_BAR" not in c
c = c.replace(old_c, old_c + '    const val SHOW_BOTTOM_BAR = "showBottomBar"\n', 1)
write(CONST, c)

DS = f"{J}/database/DataStore.kt"
d = read(DS)
old_d = "    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API)\n"
assert old_d in d and "showBottomBar" not in d
d = d.replace(old_d, old_d + '    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)\n', 1)
write(DS, d)
print("  Constants/DataStore: SHOW_BOTTOM_BAR 还原")

print("== 6) 字符串：删 bar_delay_*，还原官方 show_bottom_bar ==")
locales = ["values", "values-zh-rCN", "values-zh-rTW",
           "values-fa", "values-ja", "values-ko", "values-ru", "values-uk"]
for loc in locales:
    p = f"{R}/{loc}/strings.xml"
    s = read(p)
    s, n = re.subn(r'\n *<string name="bar_delay_[^<]*</string>', "", s)
    assert "bar_delay_" not in s, f"{p}: bar_delay_* 未清干净"
    if 'name="show_bottom_bar"' not in s:
        off_s = official(p)
        m = re.search(r' *<string name="show_bottom_bar">.*?</string>', off_s)
        assert m, f"官方 {p} 缺 show_bottom_bar"
        anchor = re.search(r'\n( *<string name="utls_fingerprint">)', s)
        assert anchor, f"{p}: 缺 utls_fingerprint 锚点"
        s = s[:anchor.start()] + "\n" + m.group(0) + s[anchor.start():]
    write(p, s)
    print(f"  {loc}: -bar_delay_* +show_bottom_bar")

print("== 7) 硬断言 ==")
errors = []
for p in official_files:
    if read(p) != official(p):
        errors.append(f"{p} 与官方不一致")
ma = read(MA)
for token in ["binding.fab.initProgress(binding.fabProgress)",
              "binding.stats.setOnClickListener",
              "private fun syncMainControls(",
              "fun driveBottomBar("]:
    if token not in ma:
        errors.append(f"MainActivity 缺官方锚点: {token}")
for token in ["connectBar", "ConnectBar", "syncConnectBar", "ServiceIconView", "027/029"]:
    if token in ma:
        errors.append(f"MainActivity 含定制托板残留: {token}")
cf = read(CF)
for token in ["setupBottomBarScrollDriver", "driveBottomBar"]:
    if token not in cf:
        errors.append(f"ConfigurationFragment 缺官方锚点: {token}")
for token in ["ViewCompat", "updatePadding", "clipToPadding = false"]:
    if token in cf:
        errors.append(f"ConfigurationFragment 含 017 残留: {token}")
gp = read(f"{R}/xml/global_preferences.xml")
if 'app:key="showBottomBar"' not in gp:
    errors.append("global_preferences 缺官方 showBottomBar 开关")
for loc in locales:
    s = read(f"{R}/{loc}/strings.xml")
    if 'name="show_bottom_bar"' not in s:
        errors.append(f"{loc} 缺 show_bottom_bar")
    if "bar_delay_" in s:
        errors.append(f"{loc} 仍有 bar_delay_*")
for token in ["ConnectBar", "ServiceIconView", "connect_bar", "bar_delay", "bg_touch_rounded"]:
    hits = []
    for f in (tree / "app/src/main").rglob("*"):
        if f.is_file() and f.suffix in {".kt", ".java", ".xml"}:
            if token in f.read_text(errors="ignore"):
                hits.append(str(f.relative_to(tree)))
    if hits:
        errors.append(f"全树残留 {token}: {hits}")

if errors:
    for e in errors:
        print(f"  FAIL {e}")
    raise SystemExit("官方托板还原校验失败")
print("PASS: 托板 = starifly 官方源码（StatsBar + ServiceButton，字节级一致），无定制残留")
