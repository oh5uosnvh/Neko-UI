package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R

/** The same theme-derived card treatment used by the ☴ group picker. No theme caches. */
object ThemedChoiceStyle {
    private const val FILL_ALPHA = 0.16f
    /** 未选中项的中性灰底强度：取 textColorPrimary 的低透明度，日/夜间主题都自动适配。 */
    private const val NEUTRAL_FILL_ALPHA = 0.09f

    private fun color(context: Context, attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        return try { values.getColor(0, Color.GRAY) } finally { values.recycle() }
    }

    fun itemCard(context: Context, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.choice_corner_radius)
        if (!selected) {
            // 未选中项：中性灰底、无描边 —— 主题色底、主题色框与粗体只属于选中项
            // （见 bind()），像「代理 / 绕过 / 屏蔽」菜单里只有当前出站项被点亮。
            val foreground = color(context, android.R.attr.textColorPrimary)
            setColor(ColorUtils.setAlphaComponent(foreground, (Color.alpha(foreground) * NEUTRAL_FILL_ALPHA).toInt()))
            return@apply
        }
        val primary = color(context, R.attr.colorPrimary)
        setColor(ColorUtils.setAlphaComponent(primary, (Color.alpha(primary) * FILL_ALPHA).toInt()))
        setStroke(
            context.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected), primary,
        )
    }

    /** Top-level header sits above scroll content: same visible tint, but never see-through. */
    fun opaqueItemCard(context: Context, selected: Boolean): GradientDrawable {
        val tint = if (selected) color(context, R.attr.colorPrimary)
            else color(context, android.R.attr.textColorPrimary)
        val fraction = if (selected) FILL_ALPHA else NEUTRAL_FILL_ALPHA
        val foreground = ColorUtils.setAlphaComponent(tint, (Color.alpha(tint) * fraction).toInt())
        val base = ColorUtils.setAlphaComponent(color(context, android.R.attr.colorBackground), 255)
        return itemCard(context, selected).apply { setColor(ColorUtils.compositeColors(foreground, base)) }
    }

    /** The label owns the card; the transparent row's insets keep every rounded edge visible. */
    fun bind(row: View, label: TextView, selected: Boolean) {
        row.isSelected = selected
        label.isSelected = selected
        label.background = itemCard(label.context, selected)
        label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        label.setTextColor(color(label.context, android.R.attr.textColorPrimary))
        label.maxWidth = (maximumPopupWidth(label.context) - dp(label.context, 20)).coerceAtLeast(1)
    }

    fun maximumPopupWidth(context: Context): Int =
        (context.resources.displayMetrics.widthPixels - dp(context, 16)).coerceAtLeast(1)

    internal fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private object MenuOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height,
                view.resources.getDimension(R.dimen.popup_corner_radius))
        }
    }

    fun clipMenu(list: ListView) {
        if (list.outlineProvider === MenuOutline && list.clipToOutline) return
        list.setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        list.divider = null
        list.dividerHeight = 0
        list.clipToPadding = true
        list.clipToOutline = true
        list.outlineProvider = MenuOutline
    }
}
