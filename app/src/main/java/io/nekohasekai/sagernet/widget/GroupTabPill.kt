package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R

/** TabView keeps click, accessibility, quick-long-press and the stable group ID binding. */
class GroupTabPill(context: Context) : AppCompatTextView(context) {
    init {
        val selected = color(R.attr.colorMaterial300)
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground = if (night) Color.WHITE else Color.BLACK
        val low = color(R.attr.colorLayerLow)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), surface(selected))
            addState(intArrayOf(), surface(low))
        }
        setTextColor(foreground)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(34)
        maxWidth = dp(264)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun surface(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(1000).toFloat()
        setColor(fill)
        setStroke(dp(1), color(R.attr.colorLayerStroke))
    }
    private fun color(attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        return try { values.getColor(0, Color.BLACK) } finally { values.recycle() }
    }

    companion object {
        fun bindTab(tab: TabLayout.Tab, label: CharSequence?) {
            tab.text = label
            tab.contentDescription = label
            tab.customView = GroupTabPill(tab.view.context).apply {
                text = label
                isSelected = tab.isSelected
            }
        }
    }
}
