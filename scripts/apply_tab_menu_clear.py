#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分组标签长按菜单 v2：自绘 PopupWindow。

用户规格（顺序固定、只有一条虚线分隔、宽度收紧）：
    编辑
    跳转分组
    ┄┄┄┄┄┄┄┄
    清空
    删除

不用系统 PopupMenu 的原因（r210 实测翻车）：
  1) MenuBuilder 按 order 排序、分组交错时渲染顺序变成 编辑/清空/跳转分组/删除；
  2) 分组分隔线在每个组变换处都画线（交错时每项之间都有线）；
  3) 菜单宽度按最长项下限撑开，右侧大片留白。
自绘后：顺序即代码顺序、只有清空之上一条虚线、宽度收缩到内容。

清空 = 确认后删除该分组全部节点（保留分组），与分组卡片 action_clear
同一套官方文案（clear_profiles / clear_profiles_message）与
GroupManager.clearGroup（单条 SQL，万级节点无压力）。
样式照搬 TopBarController 的分组快速列表（roundedCard 同参数）。
"""
from pathlib import Path
import sys

r = Path(sys.argv[1]).resolve()
p = r / "app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
s = p.read_text()

old_listener = '''                if (position in adapter.groupList.indices) {
                    val group = adapter.groupList[position]
                    // 018: 长按分组标签 → 编辑 / 跳转 / 删除（删除带还原）
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
                    }
                    popup.show()
                }'''
new_listener = '''                if (position in adapter.groupList.indices) {
                    val group = adapter.groupList[position]
                    // 018: 长按分组标签 → 编辑 / 跳转分组 / ┄┄ / 清空 / 删除（自绘菜单）
                    showTabGroupMenu(v, group)
                }'''
assert old_listener in s, "018 分组标签长按监听器形状变化，需人工核对"
s = s.replace(old_listener, new_listener, 1)

fun_code = '''    /**
     * 018: 长按分组标签菜单 —— 自绘 PopupWindow（替代系统 PopupMenu）。
     *
     * 规格：
     *   编辑
     *   跳转分组
     *   ┄┄┄┄┄┄（只在清空之上画一条虚线分隔）
     *   清空
     *   删除
     *
     * 清空 = 确认后删除该分组全部节点（保留分组本身），与分组卡片的
     * action_clear 完全同一套文案与 GroupManager.clearGroup。
     * 卡片样式与 TopBarController 分组快速列表同参：圆角
     * @dimen/popup_corner_radius、描边 @dimen/card_stroke_width +
     * @color/card_stroke、底色 ?android:attr/colorBackground。
     */
    private fun showTabGroupMenu(anchor: View, group: ProxyGroup) {
        val context = requireContext()

        fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

        fun themeColor(attr: Int): Int {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, 0xFF888888.toInt())
            ta.recycle()
            return c
        }

        var popupRef: android.widget.PopupWindow? = null

        fun menuItem(label: String, action: () -> Unit): TextView {
            val item = TextView(context)
            item.text = label
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            item.setTextColor(themeColor(android.R.attr.textColorPrimary))
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true
            )
            item.background = androidx.core.content.ContextCompat.getDrawable(
                context, ripple.resourceId
            )
            item.setPadding(dp(20), dp(13), dp(20), dp(13))
            item.isClickable = true
            item.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            item.setOnClickListener {
                popupRef?.dismiss()
                action()
            }
            return item
        }

        // 「跳转分组」与「清空」之间的那条虚线（左右各留 18dp，不顶满）
        fun dashedDivider(): View {
            val view = object : View(context) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = 0x33888888
                        strokeWidth = dp(1).toFloat()
                        pathEffect = android.graphics.DashPathEffect(
                            floatArrayOf(dp(4).toFloat(), dp(3).toFloat()), 0f
                        )
                    }
                    val y = height / 2f
                    canvas.drawLine(
                        dp(18).toFloat(), y, (width - dp(18)).toFloat(), y, paint
                    )
                }
            }
            view.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(11)
            )
            return view
        }

        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.popup_corner_radius)
                setColor(themeColor(android.R.attr.colorBackground))
                setStroke(
                    resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                    androidx.core.content.ContextCompat.getColor(context, R.color.card_stroke),
                )
            }
        }

        column.addView(
            menuItem(getString(R.string.edit)) {
                context.startActivity(
                    Intent(context, GroupSettingsActivity::class.java)
                        .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                )
            }
        )
        column.addView(
            menuItem(getString(R.string.jump_to_group)) {
                (activity as? MainActivity)?.openGroupAt(group.id)
            }
        )
        column.addView(dashedDivider())
        column.addView(
            menuItem(getString(R.string.clear_profiles)) {
                MaterialAlertDialogBuilder(context)
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
        )
        column.addView(
            menuItem(getString(R.string.delete)) {
                deleteGroupWithUndo(group)
            }
        )

        val popup = android.widget.PopupWindow(
            column,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }
        popupRef = popup
        popup.showAsDropDown(anchor, 0, dp(4))
    }

'''
anchor_fun = "    fun deleteGroupWithUndo(group: ProxyGroup) {"
assert anchor_fun in s, "缺少函数插入锚点 deleteGroupWithUndo"
s = s.replace(anchor_fun, fun_code + anchor_fun, 1)

p.write_text(s)

for token in ["showTabGroupMenu(v, group)",
              "private fun showTabGroupMenu",
              "getString(R.string.clear_profiles)",
              "GroupManager.clearGroup(group.id)",
              "DashPathEffect",
              "deleteGroupWithUndo(group)"]:
    assert token in s, f"缺少 {token}"
assert "popup.menu.add" not in s, "仍残留系统 PopupMenu 菜单项构造"
print("PASS: 分组标签长按菜单 = 编辑/跳转分组/虚线/清空/删除（自绘、宽度收紧）")
