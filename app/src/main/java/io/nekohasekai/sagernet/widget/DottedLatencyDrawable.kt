package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** The pending indicator only; stopped as soon as its holder leaves the screen. */
internal class DottedLatencyDrawable : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var tint = 0xff888888.toInt()
    private var motion: ValueAnimator? = null
    override fun draw(canvas: Canvas) {
        val radius = minOf(bounds.width(), bounds.height()) * .36f
        val dot = minOf(bounds.width(), bounds.height()) * .065f
        for (i in 0 until 8) {
            val angle = (i * 45f + phase) * Math.PI / 180.0
            paint.color = tint
            paint.alpha = 55 + i * 25
            canvas.drawCircle(bounds.exactCenterX() + (cos(angle) * radius).toFloat(),
                bounds.exactCenterY() + (sin(angle) * radius).toFloat(), dot, paint)
        }
    }
    override fun setTint(tintColor: Int) { tint = tintColor; invalidateSelf() }
    override fun start() {
        if (motion != null) return
        motion = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }
    override fun stop() { motion?.removeAllUpdateListeners(); motion?.cancel(); motion = null }
    override fun isRunning() = motion != null
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
