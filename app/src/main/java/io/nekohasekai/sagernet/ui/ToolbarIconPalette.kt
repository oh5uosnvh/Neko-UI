package io.nekohasekai.sagernet.ui

import android.content.Context
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat

/** Fixed-white vector resources must follow the PAGE ink, not the toolbar overlay's theme. */
internal object ToolbarIconPalette {
    fun foreground(context: Context): Int = context.obtainStyledAttributes(
        intArrayOf(android.R.attr.textColorPrimary)
    ).let { attrs -> attrs.getColor(0, -1).also { attrs.recycle() } }

    fun apply(toolbar: Toolbar, pageContext: Context) {
        val ink = foreground(pageContext)
        toolbar.navigationIcon = toolbar.navigationIcon?.let {
            DrawableCompat.wrap(it).mutate().also { icon -> DrawableCompat.setTint(icon, ink) }
        }
        for (index in 0 until toolbar.menu.size()) {
            val item = toolbar.menu.getItem(index)
            item.icon = item.icon?.let {
                DrawableCompat.wrap(it).mutate().also { icon -> DrawableCompat.setTint(icon, ink) }
            }
        }
    }
}
