package io.nekohasekai.sagernet.widget

import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import kotlin.math.max

/** Text ink, not TextView.bottom (font padding), fontMetrics.bottom, or toolbar.bottom. */
internal object TitleGlyphMetrics {
    fun titleView(toolbar: Toolbar): TextView? {
        if (toolbar.title.isNullOrEmpty()) return null
        var result: TextView? = null
        for (index in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(index)
            if (child is TextView && child.visibility == View.VISIBLE &&
                TextUtils.equals(child.text, toolbar.title) &&
                (result == null || child.top < result.top)) result = child
        }
        return result
    }

    fun bottom(title: TextView, ancestor: ViewGroup): Float? {
        val layout = title.layout ?: return null
        if (title.baseline < 0 || layout.lineCount == 0) return null
        val text = layout.text.toString() // Includes the TextView's transformation, if any.
        val bounds = Rect()
        var bottom = Float.NEGATIVE_INFINITY
        val lines = minOf(layout.lineCount, title.maxLines.coerceAtLeast(1))
        for (line in 0 until lines) {
            val start = layout.getLineStart(line)
            val end = layout.getLineVisibleEnd(line)
            if (end <= start) continue
            // Hidden ellipsized glyphs must not contribute descenders to the boundary.
            val count = layout.getEllipsisCount(line)
            val visible = if (count > 0) {
                val ellipsis = (start + layout.getEllipsisStart(line)).coerceIn(start, end)
                text.substring(start, ellipsis) + "\u2026" +
                    text.substring((ellipsis + count).coerceAtMost(end), end)
            } else text.substring(start, end)
            if (visible.isBlank()) continue
            title.paint.getTextBounds(visible, 0, visible.length, bounds)
            // Some color/bitmap fonts expose no ink bounds. Descent is the safe fallback;
            // bottom includes font leading and is deliberately NOT used.
            val descent = if (bounds.isEmpty) title.paint.fontMetrics.descent else bounds.bottom.toFloat()
            val baseline = title.baseline - layout.getLineBaseline(0) +
                layout.getLineBaseline(line) - title.scrollY
            bottom = max(bottom, baseline + descent)
        }
        if (!bottom.isFinite()) return null
        val point = RectF(0f, bottom, title.width.toFloat(), bottom)
        return if (mapToAncestor(title, ancestor, point)) point.bottom else null
    }

    fun bounds(view: View, ancestor: ViewGroup, out: RectF): Boolean {
        out.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        return mapToAncestor(view, ancestor, out)
    }

    private fun mapToAncestor(view: View, ancestor: ViewGroup, out: RectF): Boolean {
        var child = view
        while (child !== ancestor) {
            val parent = child.parent as? View ?: return false
            if (!child.matrix.isIdentity) child.matrix.mapRect(out)
            out.offset((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
            child = parent
        }
        return true
    }
}
