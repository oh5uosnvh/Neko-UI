package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R

/** A fixed outline with a cheap progress fill: swipe follows distance, click uses a crossfade. */
class GroupTabPill(context: Context) : AppCompatTextView(context) {
    private val surface = ProgressSurface(color(R.attr.colorLayerLow), color(R.attr.colorMaterial300),
        color(R.attr.colorLayerStroke), resources.displayMetrics.density)
    private var manualProgress = false
    private var ready = false
    var selectionProgress: Float
        get() = surface.progress
        set(value) { surface.progress = value.coerceIn(0f, 1f); invalidate() }
    /** Test hook: which edge the distance fill grows from (true = right edge). */
    internal val fillFromRight: Boolean get() = surface.fromRight
    init {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        setTextColor(if (night) Color.WHITE else Color.BLACK)
        background = surface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(34); maxWidth = dp(264); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER
        isClickable = false; isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ready = true
    }
    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        if (ready && !manualProgress) selectionProgress = if (selected) 1f else 0f
    }
    fun updateProgress(progress: Float, fromRight: Boolean, crossFade: Boolean = false) {
        manualProgress = true
        surface.fromRight = fromRight
        surface.crossFade = crossFade
        selectionProgress = progress
    }
    fun settleSelected(selected: Boolean) {
        manualProgress = false
        surface.crossFade = false
        surface.fromRight = false
        super.setSelected(selected)
        selectionProgress = if (selected) 1f else 0f
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun color(attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        return try { values.getColor(0, Color.BLACK) } finally { values.recycle() }
    }
    internal class ProgressSurface(val low: Int, val selected: Int, val stroke: Int, val density: Float) : Drawable() {
        var progress = 0f
        var fromRight = false
        var crossFade = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun draw(canvas: Canvas) {
            val edge = density / 2f
            rect.set(bounds); rect.inset(edge, edge)
            val radius = rect.height() / 2f
            fill.style = Paint.Style.FILL; fill.alpha = 255; fill.color = low
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (progress > 0f) {
                fill.color = selected
                if (crossFade) {
                    fill.alpha = (255 * progress).toInt()
                    canvas.drawRoundRect(rect, radius, radius, fill)
                } else {
                    val save = canvas.save()
                    if (fromRight) canvas.clipRect(rect.right - rect.width() * progress, rect.top, rect.right, rect.bottom)
                    else canvas.clipRect(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom)
                    canvas.drawRoundRect(rect, radius, radius, fill)
                    canvas.restoreToCount(save)
                }
            }
            fill.alpha = 255; fill.color = stroke; fill.style = Paint.Style.STROKE; fill.strokeWidth = density
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    companion object {
        fun bindTab(tab: TabLayout.Tab, label: CharSequence?) {
            tab.text = label; tab.contentDescription = label
            val existing = tab.customView as? GroupTabPill
            if (existing != null) { existing.text = label; existing.settleSelected(tab.isSelected) }
            else tab.customView = GroupTabPill(tab.view.context).apply { text = label; settleSelected(tab.isSelected) }
        }
    }
}
