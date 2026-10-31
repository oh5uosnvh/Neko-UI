#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接入分组清空的统一快照/还原 Snackbar，并恢复选择模式分组按钮。

本脚本只做两件事：
1. 把 tab 菜单和分组页的 action_clear 都接到 GroupClearAction.confirm；
2. select=true 的 ConfigurationFragment 顶栏接回右侧 ☴，复用
   TopBarController.showGroupPicker，选择器列表和普通配置页同源。
"""
from pathlib import Path
import shutil
import sys

r = Path(sys.argv[1]).resolve()
kit = Path(__file__).resolve().parents[1]
base = r / "app/src/main"

# Stage the already-tested shared clear/restore implementation and resources.
for rel in [
    "java/io/nekohasekai/sagernet/ui/GroupClearAction.kt",
    "java/io/nekohasekai/sagernet/ui/GroupClearStore.kt",
    "res/values/neko_network.xml",
    "res/values-zh-rCN/neko_network.xml",
]:
    src = kit / "files/ui-network/main" / rel
    dst = base / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    if rel.endswith("GroupClearStore.kt") and dst.exists():
        # apply_profile_batch_pipeline already installs the optimized version;
        # do not overwrite its pagination changes.
        continue
    shutil.copy2(src, dst)

# Tab menu clear action: replace the direct delete with shared confirm+undo.
p = base / "java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
s = p.read_text()
old = """                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.clearGroup(group.id)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()"""
new = """                GroupClearAction.confirm(requireActivity() as ThemedActivity, group)"""
if old in s:
    s = s.replace(old, new, 1)
elif "GroupClearAction.confirm(requireActivity() as ThemedActivity, group)" not in s:
    raise SystemExit("tab clear block not found")
p.write_text(s)

# Group page action_clear: same shared confirm+undo path.
p = base / "java/io/nekohasekai/sagernet/ui/GroupFragment.kt"
s = p.read_text()
old = """                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()"""
new = """                    GroupClearAction.confirm(activity, proxyGroup)"""
if old not in s:
    raise SystemExit("group action_clear block not found")
s = s.replace(old, new, 1)
p.write_text(s)

# Make the existing picker callable from select mode.
p = base / "java/io/nekohasekai/sagernet/ui/TopBarController.kt"
s = p.read_text()
old = "    private fun showGroupPicker(anchor: View) {"
new = "    fun showGroupPicker(anchor: View) {"
if old not in s:
    raise SystemExit("showGroupPicker declaration not found")
p.write_text(s.replace(old, new, 1))

# Select mode: add the same right-side group-picker icon and callback.
p = base / "java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
s = p.read_text()
old = """        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }"""
new = """        } else {
            toolbar.setTitle(titleRes)
            toolbar.menu.add(0, R.id.neko_picker_groups, 0, R.string.topbar_group_desc).apply {
                setIcon(R.drawable.ic_topbar_group)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.neko_picker_groups) {
                    topBar.showGroupPicker(toolbar)
                    true
                } else false
            }
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }"""
if old not in s:
    raise SystemExit("select toolbar block not found")
p.write_text(s.replace(old, new, 1))

print("PASS: clear uses shared undo Snackbar; select mode exposes right-side group picker")
