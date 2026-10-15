package io.nekohasekai.sagernet.ui

import android.graphics.*
import android.graphics.drawable.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import io.nekohasekai.sagernet.R

/** Content-measured width: no AppCompat menu minimum width or icon gutter. */
internal object GroupTabMenu {
    fun show(anchor: View, edit: () -> Unit, jump: () -> Unit, clear: () -> Unit, delete: () -> Unit) {
        val c = anchor.context
        fun dp(v: Int) = (v * c.resources.displayMetrics.density + .5f).toInt()
        fun color(attr: Int): Int {
            val a = c.obtainStyledAttributes(intArrayOf(attr)); val v = a.getColor(0, Color.GRAY); a.recycle(); return v
        }
        val foreground = color(android.R.attr.textColorPrimary)
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                setColor(color(android.R.attr.colorBackground)); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), color(android.R.attr.textColorSecondary) and 0x00ffffff or 0x40000000)
            }
        }
        val popup = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); popup.elevation = dp(8).toFloat()
        popup.isOutsideTouchable = true
        val labels = listOf(R.string.edit, R.string.jump_to_group, R.string.neko_clear, R.string.delete)
        val actions = listOf(edit, jump, clear, delete)
        val rows = labels.mapIndexed { i, label ->
            TextView(c).apply {
                setText(label); setTextColor(foreground); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(16), 0, dp(16), 0)
                val out = TypedValue(); c.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener { popup.dismiss(); actions[i]() }
            }
        }
        // All options use the longest measured title, normally “跳转分组”.
        val width = rows.maxOf { it.measure(0, 0); it.measuredWidth }.coerceAtMost(c.resources.displayMetrics.widthPixels-dp(24))
        rows.forEachIndexed { i, row ->
            if (i == 2) card.addView(object : View(c) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = foreground and 0x00ffffff or 0x55000000
                    strokeWidth = dp(1).toFloat(); pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(3).toFloat()), 0f)
                }
                override fun onDraw(canvas: Canvas) { canvas.drawLine(dp(12).toFloat(), height/2f, width-dp(12).toFloat(), height/2f, p) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9)))
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        popup.width = width
        popup.showAsDropDown(anchor, 0, dp(2), Gravity.START)
    }
}
