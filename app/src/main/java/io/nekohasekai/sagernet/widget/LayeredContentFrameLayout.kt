package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/** API 21+ content surface: only the top two corners are rounded, exactly as the MD design. */
class LayeredContentFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val clipPath = Path()
    private val clipBounds = RectF()
    private val topRadius = 24f * resources.displayMetrics.density

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clipPath.reset()
        clipBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = topRadius.coerceAtMost(minOf(width, height) / 2f).coerceAtLeast(0f)
        clipPath.addRoundRect(clipBounds,
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
