package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R

/**
 * Builds the selected-item card used by every app-owned menu surface.
 *
 * The fill is supplied by the caller so each menu keeps its existing pressed/selected
 * palette, while the perimeter always follows the active theme's primary color.  Do not
 * cache the result: menus are recreated after a day/night or accent-theme change.
 */
internal object SelectedMenuBackground {
    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else fallback
    }

    fun fromColor(context: Context, fillColor: Int, cornerRadius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            this.cornerRadius = cornerRadius
            setStroke(
                context.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected),
                themeColor(context, R.attr.colorPrimary, Color.TRANSPARENT),
            )
        }

    fun fromSurface(
        context: Context,
        surfaceColor: Int,
        cornerRadius: Float,
        fillFraction: Float = .10f,
    ): GradientDrawable {
        val primary = themeColor(context, R.attr.colorPrimary, Color.TRANSPARENT)
        return fromColor(context, ColorUtils.blendARGB(surfaceColor, primary, fillFraction), cornerRadius)
    }
}
