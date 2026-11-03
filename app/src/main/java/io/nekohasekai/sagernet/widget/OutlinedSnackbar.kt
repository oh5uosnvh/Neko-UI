package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R

/** Only decoration; official Snackbar timing, actions, Undo callbacks and anchors are untouched. */
object OutlinedSnackbar {
    fun decorate(snackbar: Snackbar): Snackbar {
        val context = snackbar.view.context
        val density = context.resources.displayMetrics.density
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val stroke = if (night) Color.WHITE else Color.BLACK
        val attr = context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerHigh))
        val fill = try { attr.getColor(0, if (night) 0xFF252628.toInt() else Color.WHITE) } finally { attr.recycle() }
        // Clear the tint before assigning the shape, otherwise Material's SRC_IN
        // tint paints over the black/white outline as well as the fill.
        snackbar.setBackgroundTintList(null)
        snackbar.view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(fill)
            setStroke((density + .5f).toInt().coerceAtLeast(1), stroke)
        }
        snackbar.setTextColor(if (night) Color.WHITE else Color.BLACK)
        snackbar.setActionTextColor(if (night) Color.WHITE else Color.BLACK)
        return snackbar
    }
}
