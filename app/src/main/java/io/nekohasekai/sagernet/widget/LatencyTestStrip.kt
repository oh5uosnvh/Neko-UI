package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The actual (non-WebView) floating latency strip.  It is deliberately one View:
 * changing a counter therefore never measures or lays out the pager/list.
 */
class LatencyTestStrip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class State { HIDDEN, RUNNING, RESULT, EMPTY }

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var typeface: Typeface = Typeface.DEFAULT
    private var sizePx = 15f * resources.displayMetrics.scaledDensity
    private var barHeight = dp(34)
    private var surfaceColor = resolve(R.attr.colorLayerHigh, Color.WHITE)
    private var foregroundColor = Color.BLACK
    private var stroke = Color.BLACK
    private var accent = resolve(R.attr.colorPrimary, Color.DKGRAY)
    private var green = ContextCompat.getColor(context, R.color.latency_green)
    private var red = ContextCompat.getColor(context, R.color.material_red_500)
    private var current = State.HIDDEN
    private var done = 0
    private var total = 0
    private var failed = 0
    private var available = 0
    private var interrupted = false
    private var message: CharSequence = ""
    private var generation = 0L
    private var resultFraction = 1f
    private var animatedWidth = 0f
    private var targetWidth = 0f
    private var contentWidth = 0f
    private var sessionWidth = 0f
    private var maxWidth = Int.MAX_VALUE
    private var widthAnimator: ValueAnimator? = null
    private var slideAnimator: ValueAnimator? = null
    /** Single scene offset; surface, glyphs and border always move together. */
    private var stripOffset = 0f
    private var stripAnimator: ValueAnimator? = null
    private var spinnerAnimator: ValueAnimator? = null
    private var retire: Runnable? = null
    private var onCancel: (() -> Unit)? = null
    private var cancelLeft = 0f
    private var counterWidth = 0f
    private var counterAnimator: ValueAnimator? = null
    private var runningSnapshot = false
    private var cancelPressed = false

    init {
        id = R.id.latency_test_strip
        visibility = GONE
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        surfacePaint.style = Paint.Style.FILL
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = max(1f, density)
        refreshTheme()
    }

    val state: State get() = current
    val isRunning: Boolean get() = current == State.RUNNING
    val displayedDone: Int get() = done
    val displayedTotal: Int get() = total
    val displayedFailed: Int get() = failed
    val displayedAvailable: Int get() = available
    val displayedMessage: CharSequence get() = message

    fun setCancelListener(listener: (() -> Unit)?) { onCancel = listener }

    /** Copies the real group-name pill's measured text metrics and height. */
    fun copyPillStyle(pill: TextView?, measuredHeight: Int) {
        var changed = false
        pill?.let {
            val nextTypeface = it.typeface ?: Typeface.DEFAULT
            if (typeface != nextTypeface || sizePx != it.textSize || textPaint.textScaleX != it.textScaleX) changed = true
            typeface = nextTypeface
            sizePx = it.textSize
            textPaint.textScaleX = it.textScaleX
        }
        if (measuredHeight > 0 && barHeight != measuredHeight) { barHeight = measuredHeight; changed = true }
        refreshTheme()
        if (changed) requestLayout()
        invalidate()
    }

    fun setAvailableWidth(width: Int) {
        maxWidth = width.coerceAtLeast(dp(48))
        targetWidth = intrinsicWidth().coerceAtMost(maxWidth).toFloat()
        if (current == State.RUNNING) sessionWidth = max(sessionWidth, targetWidth)
        if (animatedWidth == 0f || current == State.HIDDEN) animatedWidth = targetWidth
        if (current == State.RUNNING && contentWidth == 0f) contentWidth = targetWidth
        else animateContentWidth(targetWidth)
        invalidate()
    }

    fun showRunning(done: Int, total: Int, failed: Int) {
        stopRetire()
        widthAnimator?.cancel(); widthAnimator = null
        generation++
        current = State.RUNNING
        this.done = done.coerceAtLeast(0)
        this.total = total.coerceAtLeast(0)
        this.failed = failed.coerceAtLeast(0)
        message = ""
        interrupted = false
        resultFraction = 0f
        runningSnapshot = true
        stopSlide()
        visibility = VISIBLE
        accessibilityPaneTitle = "延迟测试进行中"
        contentDescription = "延迟测试：${this.done}/${this.total}，失败 ${this.failed}，可取消"
        textPaint.textSize = sizePx; textPaint.typeface = typeface
        counterWidth = textPaint.measureText("${this.done}/${this.total}")
        // Reserve enough for the eventual result so completing never resizes
        // the surface. This is a content width, not the full screen width.
        val resultBudget = textPaint.measureText("测试完成") + gap() +
            textPaint.measureText("可用:${this.total}") + gap() +
            textPaint.measureText("超时:${this.total}") + paddingStartPx() + paddingEndPx()
        val width = max(intrinsicWidth().toFloat(), resultBudget).coerceAtMost(maxWidth.toFloat())
        sessionWidth = width
        animatedWidth = width
        contentWidth = width
        targetWidth = width
        invalidate()
        translationX = 0f
        stripOffset = -width
        animateStripOffset(-width, 0f) {
            if (current == State.RUNNING) startSpinner()
        }
        startSpinner()
        invalidate()
    }

    fun updateRunning(done: Int, total: Int, failed: Int) {
        if (current != State.RUNNING) showRunning(done, total, failed) else {
            this.done = done.coerceAtLeast(0)
            this.total = total.coerceAtLeast(0)
            this.failed = failed.coerceAtLeast(0)
            contentDescription = "延迟测试：${this.done}/${this.total}，失败 ${this.failed}，可取消"
            textPaint.textSize = sizePx; textPaint.typeface = typeface
            val next = textPaint.measureText("${this.done}/${this.total}")
            counterAnimator?.removeAllListeners(); counterAnimator?.cancel()
            counterAnimator = ValueAnimator.ofFloat(counterWidth, next).apply {
                duration = 240L
                addUpdateListener { counterWidth = it.animatedValue as Float; invalidate() }
                start()
            }
            sessionWidth = max(sessionWidth, intrinsicWidth().coerceAtMost(maxWidth).toFloat())
            animateContentWidth(sessionWidth)
            invalidate()
        }
    }

    fun showResult(available: Int, failed: Int, interrupted: Boolean = false) {
        stopRetire()
        generation++
        current = State.RESULT
        stopSpinner()
        stopSlide()
        this.available = available.coerceAtLeast(0)
        this.failed = failed.coerceAtLeast(0)
        this.interrupted = interrupted
        message = ""
        resultFraction = 0f
        visibility = VISIBLE
        stopWidth()
        targetWidth = (if (sessionWidth > 0f) sessionWidth else intrinsicWidth().toFloat()).coerceAtMost(maxWidth.toFloat())
        animatedWidth = targetWidth
        contentWidth = targetWidth
        accessibilityPaneTitle = if (interrupted) "测试中断" else "测试完成"
        contentDescription = "${if (interrupted) "测试中断" else "测试完成"}，可用:${this.available}，超时:${this.failed}"
        val token = generation
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener { resultFraction = it.animatedValue as Float; invalidate() }
            doOnEnd { if (token == generation && current == State.RESULT) scheduleRetire(token) }
            start()
        }
        invalidate()
    }

    fun showEmpty(message: CharSequence) {
        val wasHidden = current == State.HIDDEN || visibility != VISIBLE
        stopRetire()
        stopStripAnimation()
        translationX = 0f
        stripOffset = 0f
        runningSnapshot = false
        generation++
        current = State.EMPTY
        stopSpinner()
        stopSlide()
        this.message = message
        this.available = 0
        this.failed = 0
        this.done = 0
        this.total = 0
        interrupted = false
        resultFraction = 0f
        visibility = VISIBLE
        stopWidth()
        sessionWidth = intrinsicWidth().coerceAtMost(maxWidth).toFloat()
        targetWidth = sessionWidth
        animatedWidth = targetWidth
        contentWidth = targetWidth
        accessibilityPaneTitle = message
        contentDescription = message
        if (wasHidden) animateStripOffset(-targetWidth, 0f)
        val token = generation
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener { resultFraction = it.animatedValue as Float; invalidate() }
            doOnEnd { if (token == generation && current == State.EMPTY) scheduleRetire(token) }
            start()
        }
        invalidate()
    }

    fun release() {
        generation++
        stopRetire(); stopSpinner(); stopSlide(); stopWidth(); stopStripAnimation()
        counterAnimator?.removeAllListeners(); counterAnimator?.cancel(); counterAnimator = null
        current = State.HIDDEN
        runningSnapshot = false
        translationX = 0f
        stripOffset = 0f
        onCancel = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> (if (maxWidth != Int.MAX_VALUE) maxWidth.toFloat() else if (animatedWidth > 0f) animatedWidth else intrinsicWidth().toFloat()).roundToInt()
        }
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> barHeight
        }
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (if (current == State.RUNNING) contentWidth else animatedWidth).coerceIn(1f, width.toFloat()); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val scene = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), h)
        canvas.translate(stripOffset, 0f)
        when (current) {
            State.RUNNING -> {
                drawSurface(canvas, w, h)
                drawRunning(canvas, w, h)
                drawBorder(canvas, w, h)
            }
            State.RESULT, State.EMPTY -> {
                // Keep the running snapshot underneath: the result is an opaque
                // surface that slides over it, not a replacement of the strip.
                if (runningSnapshot) {
                    drawSurface(canvas, w, h)
                    drawRunning(canvas, w, h)
                }
                canvas.save()
                canvas.clipRect(0f, 0f, w, h)
                canvas.translate(-w * (1f - resultFraction), 0f)
                // Surface, text AND perimeter are one scene. Drawing the
                // perimeter outside this translation was the source of the
                // visible frame/text desynchronization during completion.
                drawSurface(canvas, w, h)
                drawResult(canvas, w, h)
                drawBorder(canvas, w, h)
                canvas.restore()
            }
            State.HIDDEN -> Unit
        }
        canvas.restoreToCount(scene)
    }

    private fun drawSurface(canvas: Canvas, w: Float, h: Float) {
        surfacePaint.color = surfaceColor
        bounds.set(borderPaint.strokeWidth / 2f, borderPaint.strokeWidth / 2f,
            w - borderPaint.strokeWidth / 2f, h - borderPaint.strokeWidth / 2f)
        canvas.drawRoundRect(bounds, h / 2f, h / 2f, surfacePaint)
    }

    private fun drawBorder(canvas: Canvas, w: Float, h: Float) {
        borderPaint.color = stroke
        bounds.set(borderPaint.strokeWidth / 2f, borderPaint.strokeWidth / 2f,
            w - borderPaint.strokeWidth / 2f, h - borderPaint.strokeWidth / 2f)
        canvas.drawRoundRect(bounds, h / 2f, h / 2f, borderPaint)
    }

    private fun drawRunning(canvas: Canvas, w: Float, h: Float) {
        textPaint.color = foregroundColor; textPaint.textSize = sizePx; textPaint.typeface = typeface
        val gap = gap()
        val cy = h / 2f
        val spinnerSize = min(h * .42f, dp(18).toFloat())
        val spinnerCx = paddingStartPx() + spinnerSize / 2f
        val phase = (SystemClock.uptimeMillis() % 900L).toFloat() / 900f
        for (i in 0 until 12) {
            val a = (i / 12f + phase) * (Math.PI * 2).toFloat()
            val alpha = (45 + i * 18).coerceAtMost(235)
            spinnerPaint.color = green; spinnerPaint.alpha = alpha
            canvas.drawCircle(spinnerCx + kotlin.math.sin(a) * spinnerSize * .38f,
                cy - kotlin.math.cos(a) * spinnerSize * .38f, max(1f, density * 1.25f), spinnerPaint)
        }
        var x = spinnerCx + spinnerSize / 2f + gap
        drawText(canvas, "$done/$total", x, cy, foregroundColor)
        x += counterWidth + gap
        x = drawText(canvas, failed.toString(), x, cy, red)
        cancelLeft = w - paddingEndPx() - textPaint.measureText("取消")
        if (current == State.RUNNING) drawText(canvas, "取消", cancelLeft, cy, accent)
    }

    private fun drawResult(canvas: Canvas, w: Float, h: Float) {
        textPaint.textSize = sizePx; textPaint.typeface = typeface
        val cy = h / 2f; val gap = gap(); var x = paddingStartPx()
        val title = if (interrupted) "测试中断" else "测试完成"
        val needed = if (current == State.EMPTY) textPaint.measureText(message.toString())
            else textPaint.measureText(title) + gap + textPaint.measureText("可用:$available") +
                gap + textPaint.measureText("超时:$failed")
        val room = (w - paddingStartPx() - paddingEndPx()).coerceAtLeast(1f)
        val save = canvas.save()
        // Preserve the same outer surface even for large result counts. Only its
        // inner text group fits to the available room; the perimeter never jumps.
        canvas.translate(x, cy)
        canvas.scale(minOf(1f, room / needed.coerceAtLeast(1f)), 1f)
        canvas.translate(-x, -cy)
        if (current == State.EMPTY) drawText(canvas, message.toString(), x, cy, foregroundColor)
        else {
            x = drawText(canvas, title, x, cy, foregroundColor) + gap
            x = drawText(canvas, "可用:$available", x, cy, green) + gap
            drawText(canvas, "超时:$failed", x, cy, red)
        }
        canvas.restoreToCount(save)
    }

    private fun drawText(canvas: Canvas, value: String, x: Float, cy: Float, color: Int): Float {
        textPaint.color = color
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(value, x, baseline, textPaint)
        return x + textPaint.measureText(value)
    }

    private fun intrinsicWidth(): Int {
        textPaint.textSize = sizePx; textPaint.typeface = typeface
        val gap = gap(); val horizontal = paddingStartPx() + paddingEndPx()
        val content = when (current) {
            State.RUNNING -> min(barHeight * .42f, dp(18).toFloat()) + gap +
                textPaint.measureText("$done/$total") + gap + textPaint.measureText(failed.toString()) + gap + textPaint.measureText("取消")
            State.RESULT -> textPaint.measureText(if (interrupted) "测试中断" else "测试完成") + gap +
                textPaint.measureText("可用:$available") + gap + textPaint.measureText("超时:$failed")
            State.EMPTY -> textPaint.measureText(message.toString())
            State.HIDDEN -> textPaint.measureText("测试完成")
        }
        return ceil(content + horizontal).toInt().coerceAtLeast(dp(72))
    }

    private fun gap(): Float { textPaint.textSize = sizePx; return max(1f, textPaint.measureText("中")) }
    private fun paddingStartPx(): Float = max(dp(12).toFloat(), barHeight * .42f)
    private fun paddingEndPx(): Float = max(dp(12).toFloat(), barHeight * .42f)
    private fun dp(v: Int) = (v * density + .5f).toInt()

    private fun refreshTheme() {
        surfaceColor = resolve(R.attr.colorLayerHigh, Color.WHITE)
        val lum = (Color.red(surfaceColor) * .2126f + Color.green(surfaceColor) * .7152f + Color.blue(surfaceColor) * .0722f) / 255f
        foregroundColor = if (lum > .52f) Color.rgb(25, 25, 25) else Color.WHITE
        stroke = if (lum > .52f) Color.BLACK else Color.WHITE
        accent = resolve(R.attr.colorPrimary, foregroundColor)
        invalidate()
    }

    private fun resolve(attr: Int, fallback: Int): Int {
        val value = context.obtainStyledAttributes(intArrayOf(attr))
        return try { value.getColor(0, fallback) } finally { value.recycle() }
    }

    private fun animateContentWidth(to: Float) {
        if (to <= 0f || current == State.HIDDEN) return
        targetWidth = to
        val from = if (contentWidth > 0f) contentWidth else to
        stopWidth()
        if (kotlin.math.abs(from - to) < .5f) { contentWidth = to; invalidate(); return }
        widthAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 240L
            addUpdateListener { contentWidth = it.animatedValue as Float; invalidate() }
            doOnEnd { contentWidth = to; widthAnimator = null; invalidate() }
            start()
        }
    }

    private fun startSpinner() {
        if (spinnerAnimator?.isRunning == true) return
        spinnerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { if (current == State.RUNNING) invalidate() }
            start()
        }
    }
    private fun stopSpinner() { spinnerAnimator?.cancel(); spinnerAnimator = null }
    private fun stopSlide() {
        slideAnimator?.removeAllListeners(); slideAnimator?.removeAllUpdateListeners()
        slideAnimator?.cancel(); slideAnimator = null
    }
    private fun stopWidth() {
        widthAnimator?.removeAllListeners(); widthAnimator?.removeAllUpdateListeners()
        widthAnimator?.cancel(); widthAnimator = null
    }
    private fun stopRetire() { retire?.let(::removeCallbacks); retire = null }
    private fun stopStripAnimation() {
        stripAnimator?.removeAllListeners()
        stripAnimator?.removeAllUpdateListeners()
        stripAnimator?.cancel()
        stripAnimator = null
    }
    private fun animateStripOffset(from: Float, to: Float, end: (() -> Unit)? = null) {
        stopStripAnimation()
        stripOffset = from
        stripAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 420L
            addUpdateListener { stripOffset = it.animatedValue as Float; invalidate() }
            doOnEnd { stripOffset = to; stripAnimator = null; end?.invoke() }
            start()
        }
    }

    private fun scheduleRetire(token: Long) {
        stopRetire()
        val action = Runnable { if (token == generation && current != State.RUNNING) retract(token) }
        retire = action; postDelayed(action, 2000L)
    }
    private fun retract(token: Long) {
        if (token != generation) return
        animateStripOffset(stripOffset, -width.toFloat()) {
            if (token == generation) {
                visibility = GONE
                current = State.HIDDEN
                stripOffset = 0f
                resultFraction = 1f
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (current != State.RUNNING) return false
        val paintedWidth = contentWidth.coerceAtMost(width.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPressed = event.x >= cancelLeft - dp(8) && event.x <= paintedWidth &&
                    event.y >= 0f && event.y <= height
                return cancelPressed
            }
            MotionEvent.ACTION_UP -> {
                val activate = cancelPressed && event.x >= cancelLeft - dp(8) && event.x <= paintedWidth
                cancelPressed = false
                if (activate) { performClick(); onCancel?.invoke() }
                return activate
            }
            MotionEvent.ACTION_CANCEL -> { cancelPressed = false; return false }
        }
        return cancelPressed
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) { addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) { action() }
    }) }
}
