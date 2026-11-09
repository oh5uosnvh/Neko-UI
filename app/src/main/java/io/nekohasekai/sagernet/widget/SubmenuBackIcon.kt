package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View

/** Same 20x24dp slot; visible chevron ink matches the 15sp bold header's U. */
internal class SubmenuBackIcon(context: Context, color: Int) : View(context) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    internal fun referenceInkHeight(): Int = Rect().also { titlePaint.getTextBounds("U", 0, 1, it) }.height()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val inkHeight = referenceInkHeight().toFloat().coerceAtLeast(8f * density)
            .coerceAtMost((height - 4f * density).coerceAtLeast(1f))
        stroke.strokeWidth = 1.65f * density
        val half = (inkHeight - stroke.strokeWidth).coerceAtLeast(1f) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val arm = half * .62f
        path.reset()
        path.moveTo(cx + arm / 2, cy - half)
        path.lineTo(cx - arm / 2, cy)
        path.lineTo(cx + arm / 2, cy + half)
        canvas.drawPath(path, stroke)
    }
}
