package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Looper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
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
    private val capsuleBackground = GradientDrawable().apply {
        cornerRadius = 999f * resources.displayMetrics.density
    }
    private var capsuleColor = Color.TRANSPARENT
    private var targetCapsuleColor = Color.TRANSPARENT
    private var fadeAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var connectingAnimator: ObjectAnimator? = null
    /** Local rounded ripple for the side boxes; never paints outside its own box. */
    private fun newSideBoxRipple(): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight, R.color.control_bar_border)),
            null, mask,
        )
    }

    /**
     * 启动/连接胶囊的框内按压底色：内容层就是 [capsuleBackground]（颜色动画仍直接
     * 改它，观感不变），外面套一层胶囊形 RippleDrawable——按下时整个胶囊内部出现
     * 按压底色，涟漪也不会画出胶囊圆角以外。colorControlHighlight 跟随主题（276 曾
     * 全局置透明导致这里失去按压反馈，已恢复）。
     */
    private fun newCapsuleRipple(): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight, R.color.control_bar_border)),
            capsuleBackground, mask,
        )
    }
    private enum class Presentation { DISCONNECTED, CONNECTING, CONNECTED }
    private fun presentation(state: BaseService.State = currentState) = when (state) {
        BaseService.State.Connected -> Presentation.CONNECTED
        BaseService.State.Connecting -> Presentation.CONNECTING
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
        capsule.contentDescription = context.getString(R.string.control_bar_connect_action)
        capsule.background = newCapsuleRipple()
        speedBox.background = newSideBoxRipple()
        latencyBox.background = newSideBoxRipple()
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
        togglePending = false
        capsule.isSelected = state == BaseService.State.Connected
        capsule.contentDescription = context.getString(when (presentation()) {
            Presentation.CONNECTED -> R.string.control_bar_disconnect_action
            Presentation.CONNECTING -> R.string.control_bar_connecting
            Presentation.DISCONNECTED -> R.string.control_bar_connect_action
        })
        // Do not erase a measurement, release a test lock, or restart animations on duplicates.
        val completingStartup = previous == BaseService.State.Connecting && state == BaseService.State.Connected && testing
        if (previous != state && !completingStartup) resetMeasurements() // New Connecting clears any prior failure.
        // The host reapplies a failed startup snapshot AFTER its authoritative Stopped state.
        if (changedPresentation) {
            if (shouldAnimateCapsule()) animatePresentation() else settlePresentation()
        }
        updateCapsuleColor(shouldAnimateCapsule()) // Resolve THIS view's current application theme each time.
        updateEnabledState()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) resources.getColor(value.resourceId, context.theme) else value.data
        } else resources.getColor(fallback, context.theme)
    }

    private fun updateCapsuleIcon() {
        capsuleLabel.setText(when (presentation()) {
            Presentation.CONNECTED -> if (compactCapsule) R.string.control_bar_connected_compact
                else R.string.control_bar_connected
            Presentation.CONNECTING -> if (compactCapsule) R.string.control_bar_connecting_compact
                else R.string.control_bar_connecting
            Presentation.DISCONNECTED -> if (compactCapsule) R.string.control_bar_disconnected_compact
                else R.string.control_bar_disconnected
        })
        capsuleIcon.setColorFilter(resources.getColor(R.color.control_bar_text, context.theme))
        capsuleIcon.setImageDrawable(AppCompatResources.getDrawable(context, when (presentation()) {
            Presentation.CONNECTED -> R.drawable.ic_control_plane
            Presentation.CONNECTING -> R.drawable.ic_control_connecting
            Presentation.DISCONNECTED -> R.drawable.ic_control_power
        }))
    }

    private fun shouldAnimateCapsule() = ready && canShowReadouts()

    private fun stopConnectingAnimation() {
        connectingAnimator?.cancel()
        connectingAnimator = null
        capsuleIcon.rotation = 0f
    }

    private fun startConnectingAnimation() {
        if (!shouldAnimateCapsule() || presentation() != Presentation.CONNECTING || connectingAnimator != null) return
        val animator = ObjectAnimator.ofFloat(capsuleIcon, View.ROTATION, 0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        connectingAnimator = animator
        animator.start()
    }

    private fun cancelPresentationAnimations() {
        fadeAnimator?.removeAllListeners()
        fadeAnimator?.removeAllUpdateListeners()
        fadeAnimator?.cancel()
        fadeAnimator = null
        colorAnimator?.removeAllListeners()
        colorAnimator?.removeAllUpdateListeners()
        colorAnimator?.cancel()
        colorAnimator = null
        stopConnectingAnimation()
    }

    private fun settlePresentation() {
        cancelPresentationAnimations()
        findViewById<View>(R.id.capsule_pair).alpha = 1f
        updateCapsuleIcon()
        updateCapsuleColor(false)
        startConnectingAnimation()
    }

    private fun animatePresentation() {
        cancelPresentationAnimations()
        val pair = findViewById<View>(R.id.capsule_pair)
        val initialAlpha = pair.alpha
        var swapped = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L // One 150ms fade-out/fade-in transition, not a delayed state change.
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                if (fraction >= .5f && !swapped) {
                    swapped = true
                    updateCapsuleIcon()
                    startConnectingAnimation()
                }
                pair.alpha = if (fraction < .5f) initialAlpha * (1f - fraction * 2f)
                    else (fraction - .5f) * 2f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeAnimator = null
                    pair.alpha = 1f
                    updateCapsuleIcon()
                    startConnectingAnimation()
                }
            })
        }
        fadeAnimator = animator
        animator.start()
    }

    private fun updateCapsuleColor(animate: Boolean) {
        val color = themeColor(R.attr.colorMaterial300, R.color.brand_primary_light)
        if (animate && targetCapsuleColor == color && (colorAnimator != null || capsuleColor == color)) return
        colorAnimator?.removeAllListeners()
        colorAnimator?.removeAllUpdateListeners()
        colorAnimator?.cancel()
        colorAnimator = null
        targetCapsuleColor = color
        if (!animate || capsuleColor == Color.TRANSPARENT || capsuleColor == color) {
            capsuleColor = color
            capsuleBackground.setColor(color)
            return
        }
        val animator = ValueAnimator.ofArgb(capsuleColor, color).apply {
            duration = 150L
            addUpdateListener {
                capsuleColor = it.animatedValue as Int
                capsuleBackground.setColor(capsuleColor)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { colorAnimator = null }
            })
        }
        colorAnimator = animator
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
            (capsule.layoutParams as LinearLayout.LayoutParams).weight = if (compact) 1.05f else 1.4f
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

    fun updateSpeed(txRate: Long, rxRate: Long) = onControlThread {
        if (!canReceiveMeasurements()) return@onControlThread
        speedDown.text = context.getString(R.string.control_bar_speed_down, fmt(rxRate))
        speedUp.text = context.getString(R.string.control_bar_speed_up, fmt(txRate))
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
            latency.setTextColor(resources.getColor(if (failed) R.color.control_bar_failure else R.color.latency_green, context.theme))
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
        latency.setTextColor(resources.getColor(R.color.latency_green, context.theme))
        val unknown = context.getString(R.string.control_bar_speed_unknown)
        speedDown.text = context.getString(R.string.control_bar_speed_down, unknown)
        speedUp.text = context.getString(R.string.control_bar_speed_up, unknown)
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
        cancelPresentationAnimations()
        findViewById<View>(R.id.capsule_pair).alpha = 1f
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
        if (!shouldAnimateCapsule()) {
            cancelPresentationAnimations()
            findViewById<View>(R.id.capsule_pair).alpha = 1f
            updateCapsuleIcon()
            updateCapsuleColor(false)
            clearMeasurements() // Preserve permission togglePending; discard old test presentation.
        } else if (fadeAnimator == null) {
            updateCapsuleIcon()
            updateCapsuleColor(false)
            startConnectingAnimation()
        }
        updateEnabledState() // Becoming visible re-enables testing without a fake state report.
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (ready && (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            speedBox.isPressed = false
            latencyBox.isPressed = false
            speedBox.cancelLongPress()
            latencyBox.cancelLongPress()
        }
        return handled
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
