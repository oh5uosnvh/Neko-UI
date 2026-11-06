package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.view.animation.DecelerateInterpolator
import android.util.TypedValue
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.view.View.AccessibilityDelegate
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import java.util.Locale

/**
 * A persistent, service-driven control strip. It never starts a service or runs a URL test itself.
 * All methods/callbacks are UI-thread APIs; rates are bytes/second (tx = upload, rx = download).
 *
 * The host must cancel/generation-check asynchronous results on disconnect/profile change, and
 * call [clearMeasurements] for a profile change even when the service remains Connected. After a permission
 * request is cancelled, call [changeState] with the actual state to re-arm the toggle button.
 */
class ControlBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onToggle: (() -> Unit)? = null
    var onTest: (() -> Unit)? = null
    /** The host owns the full error detail. Only a failed latency click invokes this callback. */
    var onError: (() -> Unit)? = null
        set(value) {
            field = value
            if (ready) {
                updateLatencyDescription()
                updateEnabledState()
            }
        }

    private val speedBox: View
    private val latencyBox: View
    private val speedDown: TextView
    private val speedUp: TextView
    private val capsule: FrameLayout
    private val capsuleIcon: ImageView
    private val capsulePower: ImageView
    private val capsuleLabel: TextView
    private val latency: TextView
    private val latencyProgress: ImageView
    private var latencyAnimator: ObjectAnimator? = null
    private var latencyTransition: ValueAnimator? = null
    private var measurementGeneration = 0L
    private var testingStarted = 0L
    private var pendingMeasurement: Runnable? = null
    private var currentState = BaseService.State.Idle
    private var testing = false
    private var togglePending = false
    private var detached = false
    private var compactCapsule = false
    private var ready = false
    private var windowVisible = true
    private var failed = false
    private var fadeAnimator: ValueAnimator? = null
    private val capsuleBackground = GradientDrawable().apply {
        cornerRadius = 999f * resources.displayMetrics.density
    }
    private enum class Presentation { DISCONNECTED, CONNECTED }
    private fun presentation(state: BaseService.State = currentState) = when (state) {
        BaseService.State.Connected -> Presentation.CONNECTED
        else -> Presentation.DISCONNECTED
    }

    init {
        // This layout has a merge root containing ordinary views, NOT another ControlBar.
        LayoutInflater.from(context).inflate(R.layout.layout_control_bar, this, true)
        setBackgroundResource(R.drawable.bg_control_bar)
        clipToOutline = true
        isSaveFromParentEnabled = false // Never restore a stale connected/testing presentation.
        speedBox = findViewById(R.id.speed_box)
        latencyBox = findViewById(R.id.latency_box)
        speedDown = findViewById(R.id.speed_down)
        speedUp = findViewById(R.id.speed_up)
        capsule = findViewById(R.id.capsule)
        capsuleIcon = findViewById(R.id.capsule_icon)
        capsulePower = findViewById(R.id.capsule_power)
        capsuleLabel = findViewById(R.id.capsule_label)
        latency = findViewById(R.id.latency)
        latencyProgress = findViewById(R.id.latency_progress)

        val buttonSemantics = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        listOf(speedBox, capsule, latencyBox).forEach { it.accessibilityDelegate = buttonSemantics }
        speedBox.setOnClickListener { requestReadoutAction() }
        latencyBox.setOnClickListener { requestReadoutAction() }
        capsule.setOnClickListener { requestToggle() }
        capsule.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.9f).scaleY(.9f)
                    .setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(300L).setInterpolator(PathInterpolator(.3f, 1.55f, .45f, 1f)).start()
            }
            false
        }
        capsule.contentDescription = context.getString(R.string.control_bar_connect_action)
        // Keep the persistent THEME capsule; remove only transient press/focus feedback.
        capsule.background = capsuleBackground
        capsule.foreground = null
        // The readout areas remain clickable without drawing an inset outline or capsule halo.
        foreground = null
        setOnClickListener { requestReadoutAction() }
        speedBox.background = null
        latencyBox.background = null
        ready = true
        resetMeasurements()
        settlePresentation()
        updateEnabledState()
    }

    /** Service state is authoritative; same-state permission cancellation still re-arms toggle. */
    fun changeState(state: BaseService.State) {
        if (Looper.myLooper() != Looper.getMainLooper()) { post { changeState(state) }; return }
        val previous = currentState
        val changedPresentation = presentation(previous) != presentation(state)
        currentState = state
        updateCapsuleSurface()
        togglePending = false
        capsule.isSelected = state == BaseService.State.Connected
        capsule.contentDescription = context.getString(when {
            state == BaseService.State.Connecting -> R.string.control_bar_connecting
            state == BaseService.State.Connected -> R.string.control_bar_disconnect_action
            else -> R.string.control_bar_connect_action
        })
        // Do not erase a measurement, release a test lock, or restart animations on duplicates.
        val completingStartup = previous == BaseService.State.Connecting && state == BaseService.State.Connected && testing
        if (previous != state && !completingStartup) resetMeasurements() // New Connecting clears any prior failure.
        // The host reapplies a failed startup snapshot AFTER its authoritative Stopped state.
        if (changedPresentation) {
            if (shouldAnimateCapsule()) animatePresentation() else settlePresentation()
        }
        updateEnabledState()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) resources.getColor(value.resourceId, context.theme) else value.data
        } else resources.getColor(fallback, context.theme)
    }

    private fun updateCapsuleSurface() {
        val connected = presentation() == Presentation.CONNECTED
        val base = themeColor(R.attr.colorMaterial300, R.color.brand_primary_light)
        fun mixWhite(color: Int): Int = Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * .22f).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * .22f).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * .22f).toInt(),
        )
        val top = if (connected) mixWhite(base) else resources.getColor(R.color.control_bar_off_top, context.theme)
        val bottom = if (connected) base else resources.getColor(R.color.control_bar_off_bottom, context.theme)
        capsuleBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        capsuleBackground.colors = intArrayOf(top, bottom)
    }

    private fun capsuleForeground(): Int {
        if (presentation() != Presentation.CONNECTED) return Color.WHITE
        val c = themeColor(R.attr.colorMaterial300, R.color.brand_primary_light)
        val brightness = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return if (brightness > .62) Color.argb(199, 0, 0, 0) else Color.WHITE
    }

    private fun updateCapsuleIcon() {
        updateCapsuleSurface()
        capsuleLabel.setText(when (presentation()) {
            Presentation.CONNECTED -> if (compactCapsule) R.string.control_bar_connected_compact
                else R.string.control_bar_connected
            Presentation.DISCONNECTED -> if (compactCapsule) R.string.control_bar_disconnected_compact
                else R.string.control_bar_disconnected
        })
        capsulePower.setColorFilter(Color.WHITE)
        capsuleIcon.setColorFilter(capsuleForeground())
        capsuleLabel.setTextColor(capsuleForeground())
        capsuleIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_control_plane))
    }

    private fun shouldAnimateCapsule() = ready && canShowReadouts() &&
        (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    private fun cancelPresentationAnimations() {
        fadeAnimator?.removeAllListeners()
        fadeAnimator?.removeAllUpdateListeners()
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun setIconProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val pair = findViewById<View>(R.id.capsule_pair)
        capsulePower.alpha = 1f - p
        capsulePower.scaleX = 1f - .6f * progress
        capsulePower.scaleY = capsulePower.scaleX
        capsulePower.rotation = -90f * progress
        pair.alpha = p
        pair.scaleX = .4f + .6f * progress
        pair.scaleY = pair.scaleX
        pair.rotation = 90f * (1f - progress)
    }

    private fun settlePresentation() {
        cancelPresentationAnimations()
        capsuleIcon.animate().cancel()
        capsuleIcon.translationX = 0f
        capsuleIcon.translationY = 0f
        capsuleIcon.rotation = 0f
        updateCapsuleIcon()
        setIconProgress(if (presentation() == Presentation.CONNECTED) 1f else 0f)
    }

    private fun animatePresentation() {
        cancelPresentationAnimations()
        capsuleIcon.animate().cancel()
        capsuleIcon.translationX = 0f
        capsuleIcon.translationY = 0f
        capsuleIcon.rotation = 0f
        updateCapsuleIcon()
        val connected = presentation() == Presentation.CONNECTED
        val initial = findViewById<View>(R.id.capsule_pair).alpha
        val target = if (connected) 1f else 0f
        val curve = PathInterpolator(.34f, 1.56f, .64f, 1f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                setIconProgress(initial + (target - initial) * curve.getInterpolation(fraction))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeAnimator = null
                    setIconProgress(target)
                }
            })
        }
        if (connected) {
            val dp = resources.displayMetrics.density
            capsuleIcon.translationY = 4f * dp
            capsuleIcon.animate().translationX(-2f * dp).translationY(-2f * dp).rotation(-9f)
                .setDuration(247L).setInterpolator(DecelerateInterpolator()).withEndAction {
                    capsuleIcon.animate().translationX(0f).translationY(0f).rotation(0f)
                        .setDuration(303L).start()
                }.start()
        }
        fadeAnimator = animator
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density
        val compact = widthDp < 420f && resources.configuration.fontScale > 1.3f
        if (compact != compactCapsule) {
            compactCapsule = compact
            updateCapsuleIcon() // Always an 18dp icon beside a readable label, never stacked.
            val content = getChildAt(0)
            val density = resources.displayMetrics.density
            content.setPaddingRelative(((if (compact) 6 else 18) * density).toInt(),
                content.paddingTop, (18 * density).toInt(), content.paddingBottom)
            val capsulePadding = ((if (compact) 4 else 8) * density).toInt()
            capsule.setPaddingRelative(capsulePadding, capsule.paddingTop, capsulePadding, capsule.paddingBottom)
            (speedBox.layoutParams as LinearLayout.LayoutParams).weight = if (compact) 1.35f else 1f
            (latencyBox.layoutParams as LinearLayout.LayoutParams).weight = 1f
            // Compact space budget still keeps the right label at exactly 18dp + 4dp from end.
            speedBox.setPaddingRelative(speedBox.paddingStart, speedBox.paddingTop,
                ((if (compact) 0 else 4) * density).toInt(), speedBox.paddingBottom)
            latencyBox.setPaddingRelative(((if (compact) 0 else 4) * density).toInt(),
                latencyBox.paddingTop, (4 * density).toInt(), latencyBox.paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Preference/probe callbacks can fire on Default workers; views and animators are main-only. */
    private fun onControlThread(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else post(block)
    }

    private class SpeedTriangle(private val down: Boolean, private val density: Float, private val color: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (9f * density).toInt()
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                          top: Int, y: Int, bottom: Int, paint: Paint) {
            val triangle = Path()
            val center = (top + bottom) / 2f
            val half = 4.5f * density
            val height = 7f * density
            if (down) {
                triangle.moveTo(x, center - height / 2)
                triangle.lineTo(x + 2 * half, center - height / 2)
                triangle.lineTo(x + half, center + height / 2)
            } else {
                triangle.moveTo(x, center + height / 2)
                triangle.lineTo(x + 2 * half, center + height / 2)
                triangle.lineTo(x + half, center - height / 2)
            }
            triangle.close()
            val trianglePaint = Paint(paint).apply { this.color = this@SpeedTriangle.color; style = Paint.Style.FILL }
            canvas.drawPath(triangle, trianglePaint)
        }
    }

    private fun setSpeed(view: TextView, resource: Int, value: String) {
        val content = SpannableString(context.getString(resource, value))
        val density = resources.displayMetrics.density
        content.setSpan(SpeedTriangle(resource == R.string.control_bar_speed_down, density,
            resources.getColor(R.color.control_bar_text, context.theme)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val unitStart = content.lastIndexOf(' ') + 1
        if (unitStart > 0 && unitStart < content.length) {
            content.setSpan(ForegroundColorSpan(resources.getColor(R.color.control_bar_unit, context.theme)),
                unitStart, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.setSpan(RelativeSizeSpan(.875f), unitStart, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.setTextColor(resources.getColor(R.color.control_bar_speed_text, context.theme))
        view.text = content
    }

    fun updateSpeed(txRate: Long, rxRate: Long) = onControlThread {
        if (!canReceiveMeasurements()) return@onControlThread
        setSpeed(speedDown, R.string.control_bar_speed_down, fmt(rxRate))
        setSpeed(speedUp, R.string.control_bar_speed_up, fmt(txRate))
        updateSpeedDescription()
    }

    /** Idempotent; a test acquires its lock synchronously before invoking the host. */
    fun showTesting() = onControlThread {
        if (!canReceiveMeasurements() || testing) return@onControlThread
        beginLatencyTesting()
    }

    fun showConnectionTesting() = onControlThread {
        if (!canShowReadouts() || currentState != BaseService.State.Connecting || testing) return@onControlThread
        beginLatencyTesting()
    }

    private fun beginLatencyTesting() {
        cancelLatencyPresentation()
        measurementGeneration++
        testingStarted = SystemClock.uptimeMillis()
        testing = true
        failed = false
        latency.text = ""
        latency.setTextColor(resources.getColor(R.color.latency_green, context.theme))
        latency.visibility = View.INVISIBLE
        latencyProgress.visibility = View.VISIBLE
        latencyProgress.alpha = 0f
        latencyProgress.setColorFilter(resources.getColor(R.color.latency_green, context.theme))
        latencyAnimator = ObjectAnimator.ofFloat(latencyProgress, View.ROTATION, 0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        latencyTransition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            addUpdateListener { latencyProgress.alpha = it.animatedValue as Float }
            start()
        }
        updateLatencyDescription()
        updateEnabledState()
    }

    fun showLatency(ms: Int) = onControlThread { finishLatency(ms, null) }

    fun showFailure(summary: String) = onControlThread { finishLatency(-1, summary) }

    private fun finishLatency(ms: Int, error: String?) {
        val wait = if (testing) (150L - (SystemClock.uptimeMillis() - testingStarted)).coerceAtLeast(0) else 0L
        pendingMeasurement?.let { removeCallbacks(it) }
        val generation = measurementGeneration
        val completion = Runnable {
            if (generation != measurementGeneration) return@Runnable
            pendingMeasurement = null
            if (!canShowReadouts()) {
                resetMeasurements()
                updateEnabledState()
                return@Runnable
            }
            testing = false
            failed = error != null
            latency.text = if (error != null) error.lineSequence().firstOrNull()?.trim()?.take(32)
                ?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.control_bar_latency_unknown)
            else if (currentState != BaseService.State.Connected || ms < 0) context.getString(R.string.control_bar_latency_unknown)
            else context.getString(R.string.control_bar_latency_value, ms.toString())
            latency.setTextColor(resources.getColor(when {
                failed -> R.color.control_bar_failure
                currentState == BaseService.State.Connected -> R.color.latency_green
                else -> R.color.control_bar_text
            }, context.theme))
            latencyAnimator?.cancel(); latencyAnimator = null
            latencyTransition?.removeAllUpdateListeners(); latencyTransition?.cancel()
            latencyTransition = null
            latency.visibility = View.VISIBLE
            if (latencyProgress.visibility == View.VISIBLE) {
                latency.alpha = 0f
                latencyTransition = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 100L
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        latency.alpha = fraction
                        latencyProgress.alpha = 1f - fraction
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            latency.alpha = 1f
                            latencyProgress.visibility = View.GONE
                            latencyProgress.rotation = 0f
                            latencyTransition = null
                        }
                    })
                    start()
                }
            } else latency.alpha = 1f
            updateLatencyDescription()
            updateEnabledState()
        }
        if (wait > 0L) {
            pendingMeasurement = completion
            postDelayed(completion, wait)
        } else completion.run()
    }

    private fun cancelLatencyPresentation() {
        pendingMeasurement?.let { removeCallbacks(it) }; pendingMeasurement = null
        latencyAnimator?.cancel(); latencyAnimator = null
        latencyTransition?.removeAllListeners(); latencyTransition?.removeAllUpdateListeners()
        latencyTransition?.cancel(); latencyTransition = null
        latencyProgress.rotation = 0f
        latencyProgress.alpha = 1f
        latencyProgress.visibility = View.GONE
        latency.alpha = 1f
        latency.visibility = View.VISIBLE
    }

    private fun canShowReadouts() = !detached && isAttachedToWindow && isShown && windowVisible

    private fun canReceiveMeasurements() = canShowReadouts() && currentState == BaseService.State.Connected

    private fun requestReadoutAction() {
        if (!canShowReadouts() || testing) return
        if (failed && currentState != BaseService.State.Connected) {
            onError?.invoke()
            return
        }
        requestTest()
    }

    /**
     * Clears rates/latency and the test lock, but preserves the real state and any pending toggle.
     * Use for profile/background/page invalidation after the host cancels its test generation.
     * In particular, leaving for the VPN permission activity must not re-arm a pending start.
     */
    fun clearMeasurements() = onControlThread {
        resetMeasurements()
        updateEnabledState()
    }

    /** Clears measurements and all request locks, without inventing a new service state. */
    fun reset() = onControlThread {
        togglePending = false
        resetMeasurements()
        updateEnabledState()
    }

    private fun requestTest() {
        if (!canReceiveMeasurements() || testing) return
        val callback = onTest ?: return
        showTesting() // Synchronous acquisition blocks double taps and re-entrant callbacks.
        try {
            callback()
        } catch (error: Exception) {
            showLatency(-1)
            throw error
        }
    }

    private fun requestToggle() {
        if (!canShowReadouts() || togglePending || isTransitioning()) return
        val callback = onToggle ?: return
        // No optimistic state/icon/latency changes. Lock until the host reports its real state.
        togglePending = true
        updateEnabledState()
        try {
            callback()
        } catch (error: Exception) {
            togglePending = false
            updateEnabledState()
            throw error
        }
    }

    private fun isTransitioning() = currentState == BaseService.State.Connecting ||
        currentState == BaseService.State.Stopping

    private fun updateEnabledState() {
        capsule.isEnabled = canShowReadouts() && !togglePending && !isTransitioning()
        val canTest = canShowReadouts() && !testing &&
            (currentState == BaseService.State.Connected || (failed && onError != null))
        speedBox.isEnabled = canTest
        latencyBox.isEnabled = canTest
    }

    private fun resetMeasurements() {
        measurementGeneration++
        cancelLatencyPresentation()
        testing = false
        failed = false
        latency.setTextColor(resources.getColor(
            if (currentState == BaseService.State.Connected) R.color.latency_green else R.color.control_bar_text,
            context.theme,
        ))
        val unknown = context.getString(R.string.control_bar_speed_unknown)
        setSpeed(speedDown, R.string.control_bar_speed_down, unknown)
        setSpeed(speedUp, R.string.control_bar_speed_up, unknown)
        latency.setText(R.string.control_bar_latency_unknown)
        updateSpeedDescription()
        updateLatencyDescription()
    }

    private fun updateSpeedDescription() {
        speedBox.contentDescription = context.getString(
            R.string.control_bar_speed_description, speedDown.text, speedUp.text
        )
    }

    private fun updateLatencyDescription() {
        latencyBox.contentDescription = context.getString(
            if (failed && onError != null) R.string.control_bar_failure_description
            else R.string.control_bar_latency_description, latency.text
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        settlePresentation()
        updateEnabledState()
    }

    override fun onDetachedFromWindow() {
        detached = true
        settlePresentation()
        capsule.animate().cancel()
        capsule.scaleX = 1f
        capsule.scaleY = 1f
        reset() // No animation/test state survives removal/destruction.
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready) syncAnimationVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == View.VISIBLE
        if (ready) syncAnimationVisibility()
    }

    private fun syncAnimationVisibility() {
        if (!canShowReadouts()) {
            settlePresentation()
            clearMeasurements() // Preserve permission togglePending; discard old test presentation.
        } else if (fadeAnimator == null) {
            updateCapsuleIcon()
        }
        updateEnabledState() // Becoming visible re-enables testing without a fake state report.
    }

    companion object {
        /** Decimal SI units, Locale.ROOT punctuation, negative input clamped to zero. */
        @JvmStatic
        fun fmt(bytesPerSecond: Long): String {
            val rate = bytesPerSecond.coerceAtLeast(0L)
            return when {
                // Preserve the MD kB/MB tiers; extreme real rates get readable larger SI units.
                rate >= 1_000_000_000_000_000_000L -> String.format(Locale.ROOT, "%.2f EB/s", rate / 1_000_000_000_000_000_000.0)
                rate >= 1_000_000_000_000_000L -> String.format(Locale.ROOT, "%.2f PB/s", rate / 1_000_000_000_000_000.0)
                rate >= 1_000_000_000_000L -> String.format(Locale.ROOT, "%.2f TB/s", rate / 1_000_000_000_000.0)
                rate >= 1_000_000_000L -> String.format(Locale.ROOT, "%.2f GB/s", rate / 1_000_000_000.0)
                rate >= 1_000_000L -> String.format(Locale.ROOT, "%.2f MB/s", rate / 1_000_000.0)
                rate >= 100_000L -> "${rate / 1_000L} kB/s"
                rate >= 10_000L -> String.format(Locale.ROOT, "%.1f kB/s", rate / 1_000.0)
                else -> String.format(Locale.ROOT, "%.2f kB/s", rate / 1_000.0)
            }
        }
    }
}
