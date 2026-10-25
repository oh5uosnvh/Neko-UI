#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分组标签长按菜单补全：编辑 / 跳转分组 / ── / 清空 / 删除。

用户规格（与早期版本一致）：
    编辑
    跳转分组
    ──────────
    清空
    删除

清空 = 确认后删除该分组全部节点（保留分组本身），复用官方字符串
clear_profiles / clear_profiles_message 与 GroupManager.clearGroup（单条
SQL deleteAll，万级节点无压力），与分组页既有 action_clear 行为一致。
分隔线用 MenuCompat.setGroupDividerEnabled 的菜单分组实现。
"""
from pathlib import Path
import sys

r = Path(sys.argv[1]).resolve()
p = r / "app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
s = p.read_text()

old = '''                    // 018: 长按分组标签 → 编辑 / 跳转 / 删除（删除带还原）
                    val popup = PopupMenu(requireContext(), v)
                    popup.menu.add(0, 1, 0, R.string.edit)
                    popup.menu.add(0, 2, 1, R.string.jump_to_group)
                    popup.menu.add(0, 3, 2, R.string.delete)
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> context?.startActivity(
                                Intent(context, GroupSettingsActivity::class.java)
                                    .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                            )

                            2 -> (activity as? MainActivity)?.openGroupAt(group.id)

                            3 -> deleteGroupWithUndo(group)
                        }
                        true
                    }'''
new = '''                    // 018: 长按分组标签 → 编辑 / 跳转分组 / ── / 清空 / 删除（删除带还原）
                    val popup = PopupMenu(requireContext(), v)
                    popup.menu.add(0, 1, 0, R.string.edit)
                    popup.menu.add(0, 2, 1, R.string.jump_to_group)
                    // 分组分隔线之下：清空（确认后删除该分组全部节点）/ 删除分组
                    popup.menu.add(1, 4, 0, R.string.clear_profiles)
                    popup.menu.add(1, 3, 1, R.string.delete)
                    MenuCompat.setGroupDividerEnabled(popup.menu, true)
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> context?.startActivity(
                                Intent(context, GroupSettingsActivity::class.java)
                                    .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                            )

                            2 -> (activity as? MainActivity)?.openGroupAt(group.id)

                            3 -> deleteGroupWithUndo(group)

                            4 -> MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.confirm)
                                .setMessage(R.string.clear_profiles_message)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        GroupManager.clearGroup(group.id)
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                        true
                    }'''
assert old in s, "分组标签长按菜单形状变化，需人工核对"
s = s.replace(old, new, 1)

if "import androidx.core.view.MenuCompat" not in s:
    anchor = "import androidx.core.view.isGone\n"
    assert anchor in s, "缺少 MenuCompat 插入锚点"
    s = s.replace(anchor, "import androidx.core.view.MenuCompat\n" + anchor, 1)

p.write_text(s)

for token in ["R.string.clear_profiles", "MenuCompat.setGroupDividerEnabled",
              "GroupManager.clearGroup(group.id)", "clear_profiles_message"]:
    assert token in s, f"缺少 {token}"
print("PASS: 长按分组标签菜单 = 编辑 / 跳转分组 / ── / 清空 / 删除")
