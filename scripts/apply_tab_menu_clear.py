#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分组标签长按菜单 v3：自绘 PopupWindow + 内容自适应宽度。

v3 修复（r211 实测翻车点）：
  根因——WRAP_CONTENT 的竖向 LinearLayout 里，宽为 MATCH_PARENT 的子行经
  ViewGroup.getChildMeasureSpec(AT_MOST, MATCH_PARENT) 变成 EXACTLY(可用宽)，
  每一行都被量成接近整屏宽，面板随之撑满（截图 1 的"这么宽"）。
  修复——先以 UNSPECIFIED 量出最宽行，再把所有行（含虚线）显式设为该宽度；
  面板宽度随之收紧到内容宽度（截图 2 参考效果：左右各约 15dp 留白）。

规格（顺序固定、只有清空之上一条虚线、宽度收紧）：
    编辑
    跳转分组
    ┄┄┄┄┄┄┄┄
    清空
    删除

清空 = 确认后删除该分组全部节点（保留分组），与分组卡片 action_clear
同一套官方文案与 GroupManager.clearGroup。样式与 TopBarController 分组
快速列表同参（12dp 圆角 / 1dp 描边 / 主题底色），行右溢出时自动左移。
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
     * 宽度收缩到内容：最宽项只有四个汉字，面板宽 = 最宽行实测宽。
     * 不用 MATCH_PARENT 行宽 —— WRAP_CONTENT 父布局里它会被
     * getChildMeasureSpec 放大成整屏可用宽（r211 的过宽根因）。
     * 行右溢出屏幕时整体左移，保证不裁边。
     * 清空 = 确认后删除该分组全部节点（保留分组本身），与分组卡片
     * action_clear 完全同一套文案与 GroupManager.clearGroup。
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
            item.setPadding(dp(16), dp(11), dp(16), dp(11))
            item.isClickable = true
            item.setOnClickListener {
                popupRef?.dismiss()
                action()
            }
            return item
        }

        // 「跳转分组」与「清空」之间的那条虚线（左右各留 16dp，不顶满）
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
                        dp(16).toFloat(), y, (width - dp(16)).toFloat(), y, paint
                    )
                }
            }
            view.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(9)
            )
            return view
        }

        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
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

        val rows = arrayListOf<View>()
        fun addRow(build: () -> View) {
            val v = build()
            rows.add(v)
            column.addView(v)
        }
        addRow {
            menuItem(getString(R.string.edit)) {
                context.startActivity(
                    Intent(context, GroupSettingsActivity::class.java)
                        .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                )
            }
        }
        addRow {
            menuItem(getString(R.string.jump_to_group)) {
                (activity as? MainActivity)?.openGroupAt(group.id)
            }
        }
        addRow { dashedDivider() }
        addRow {
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
        }
        addRow {
            menuItem(getString(R.string.delete)) {
                deleteGroupWithUndo(group)
            }
        }

        // 统一行宽 = 最宽行实测宽：面板宽度随内容收紧，右侧不再留白
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var maxW = 0
        for (row in rows) {
            row.measure(spec, spec)
            maxW = maxOf(maxW, row.measuredWidth)
        }
        for (row in rows) {
            val lp = row.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.width = maxW
            row.layoutParams = lp
        }

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

        // 面板超出屏幕右缘时整体左移（-1 = 宽高不变）
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val overflow = loc[0] + maxW + dp(8) - resources.displayMetrics.widthPixels
        if (overflow > 0) popup.update(anchor, -overflow, dp(4), -1, -1)
    }

'''
anchor_fun = "    fun deleteGroupWithUndo(group: ProxyGroup) {"
assert anchor_fun in s, "缺少函数插入锚点 deleteGroupWithUndo"
s = s.replace(anchor_fun, fun_code + anchor_fun, 1)

p.write_text(s)

for token in ["showTabGroupMenu(v, group)",
              "private fun showTabGroupMenu",
              "lp.width = maxW",
              "row.measure(spec, spec)",
              "getString(R.string.clear_profiles)",
              "GroupManager.clearGroup(group.id)",
              "DashPathEffect",
              "deleteGroupWithUndo(group)"]:
    assert token in s, f"缺少 {token}"
assert "popup.menu.add" not in s, "仍残留系统 PopupMenu 菜单项构造"
print("PASS: 分组标签长按菜单 = 编辑/跳转分组/虚线/清空/删除（宽度收紧到内容）")
