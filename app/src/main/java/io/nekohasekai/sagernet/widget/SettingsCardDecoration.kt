package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import kotlin.math.max
import kotlin.math.min

/** Material node/about card tokens, without elevation overlays altering the layer palette. */
internal class SettingsCardStyle(context: Context) {
    val inset = context.resources.getDimensionPixelSize(R.dimen.settings_card_inset)
    val gap = context.resources.getDimensionPixelSize(R.dimen.settings_card_gap)
    val contentPadding = context.resources.getDimensionPixelSize(R.dimen.settings_card_content_padding)
    val fill: Int
    val stroke: Int
    val radius: Float
    val strokeWidth: Float

    init {
        val layers = context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerHigh))
        fill = layers.getColor(0, 0)
        layers.recycle()
        val card = context.obtainStyledAttributes(null, intArrayOf(
            R.attr.strokeColor, R.attr.strokeWidth, R.attr.cardCornerRadius,
        ), R.attr.materialCardViewStyle, R.style.Widget_SagerNet_CardView)
        stroke = card.getColor(0, 0)
        strokeWidth = card.getDimensionPixelSize(1,
            context.resources.getDimensionPixelSize(R.dimen.card_stroke_width)).toFloat()
        radius = card.getDimension(2, context.resources.getDimension(R.dimen.card_corner_radius))
        card.recycle()
    }
}

/**
 * One fill/outline per attached section, never per row. Missing ends extend beyond the canvas
 * clip by a radius, so long partly scrolled cards cannot grow a false rounded cap at the viewport.
 * Both indexing and empty-category filtering happen in the adapter, not in onDraw().
 */
internal class SettingsCardDecoration(
    context: Context,
    private val adapter: SettingsCardAdapter,
) : RecyclerView.ItemDecoration() {
    internal val style = SettingsCardStyle(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@SettingsCardDecoration.style.fill
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@SettingsCardDecoration.style.stroke
        this.style = Paint.Style.STROKE
        strokeWidth = this@SettingsCardDecoration.style.strokeWidth
    }
    private val bounds = RectF()
    private val slots = android.util.SparseIntArray()
    // Reused; capacity depends on attached rows only, not the full adapter size.
    private var tops = FloatArray(0)
    private var bottoms = FloatArray(0)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(0, 0, 0, 0)
        val position = parent.getChildAdapterPosition(view)
        val section = adapter.index.sectionAt.getOrNull(position) ?: return
        val range = adapter.index.sections[section]
        outRect.left = style.inset
        outRect.right = style.inset
        if (position == range.first) outRect.top = style.gap + style.contentPadding
        if (position == range.last) {
            outRect.bottom = style.contentPadding
            if (position == adapter.itemCount - 1) outRect.bottom += style.gap
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0 || adapter.itemCount == 0 || state.isPreLayout) return
        if (tops.size < count) {
            tops = FloatArray(count)
            bottoms = FloatArray(count)
        }
        slots.clear()
        val clipTop = if (parent.clipToPadding) parent.paddingTop.toFloat() else 0f
        val clipBottom = (parent.height - if (parent.clipToPadding) parent.paddingBottom else 0).toFloat()
        val extension = style.radius + style.strokeWidth + 1f
        var used = 0
        // RecyclerView attaches visible rows (and a small layout cache). Stable grouping IDs
        // are constant-time lookups; no getItem/list-wide walk or per-frame path allocations.
        for (i in 0 until count) {
            val view = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(view)
            val section = adapter.index.sectionAt.getOrNull(position) ?: continue
            val range = adapter.index.sections[section]
            val params = view.layoutParams as RecyclerView.LayoutParams
            val top = if (position == range.first) {
                view.top + view.translationY - params.topMargin - style.contentPadding
            } else min(clipTop, view.top + view.translationY) - extension
            val bottom = if (position == range.last) {
                view.bottom + view.translationY + params.bottomMargin + style.contentPadding
            } else max(clipBottom, view.bottom + view.translationY) + extension
            // SparseIntArray is reused and handles transient out-of-order change-animation
            // duplicates without a quadratic attached-group scan or drawing a stroke twice.
            var slot = slots.get(section, -1)
            if (slot < 0) {
                slot = used++
                slots.append(section, slot)
                tops[slot] = top
                bottoms[slot] = bottom
            } else {
                if (position == range.first) tops[slot] = top
                if (position == range.last) bottoms[slot] = bottom
            }
        }
        val saved = canvas.save()
        canvas.clipRect(parent.paddingLeft.toFloat(), clipTop,
            (parent.width - parent.paddingRight).toFloat(), clipBottom)
        val left = (parent.paddingLeft + style.inset).toFloat()
        val right = (parent.width - parent.paddingRight - style.inset).toFloat()
        for (i in 0 until used) {
            if (bottoms[i] <= clipTop || tops[i] >= clipBottom || right <= left) continue
            bounds.set(left, tops[i], right, bottoms[i])
            canvas.drawRoundRect(bounds, style.radius, style.radius, fill)
            if (style.strokeWidth > 0f) {
                val halfStroke = style.strokeWidth / 2f
                bounds.inset(halfStroke, halfStroke)
                val radius = max(0f, style.radius - halfStroke)
                canvas.drawRoundRect(bounds, radius, radius, stroke)
            }
        }
        canvas.restoreToCount(saved)
    }
}
