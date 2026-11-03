package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
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
    private val sideMask = CapsuleExclusionMask(resources.displayMetrics.density)
    private val sideRipple = RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight, R.color.control_bar_border)),
        null, sideMask,
    )
    private var sidePressed = false
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
        capsule.background = capsuleBackground
        // A SINGLE outer-frame ripple, with a rounded capsule hole; never two box rectangles.
        foreground = sideRipple
        speedBox.background = null
        latencyBox.background = null
        ready = true
        resetMeasurements()
        settlePresentation()
        updateEnabledState()
    }

    /** Service state is authoritative; same-state permission cancellation still re-arms toggle. */
    fun changeState(state: BaseService.State) {
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
        if (previous != state) resetMeasurements() // New Connecting clears any prior failure.
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

    private fun shouldAnimateCapsule() = ready && !detached && isAttachedToWindow && isShown &&
        windowVisible && windowVisibility == View.VISIBLE

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
        val color = if (currentState == BaseService.State.Connected)
            themeColor(R.attr.colorPrimary, R.color.brand_primary)
        else themeColor(R.attr.colorMaterial300, R.color.brand_primary_light)
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

    fun updateSpeed(txRate: Long, rxRate: Long) {
        if (!canReceiveMeasurements()) return
        speedDown.text = context.getString(R.string.control_bar_speed_down, fmt(rxRate))
        speedUp.text = context.getString(R.string.control_bar_speed_up, fmt(txRate))
        updateSpeedDescription()
    }

    /** Idempotent; the click handler also acquires this lock before calling the host. */
    fun showTesting() {
        if (!canReceiveMeasurements() || testing) return
        failed = false
        latency.setTextColor(resources.getColor(R.color.control_bar_text, context.theme))
        testing = true
        latency.setText(R.string.control_bar_testing)
        updateLatencyDescription()
        updateEnabledState()
    }

    /** Service-owned startup probe presentation, independent of onTest/manual test acquisition. */
    fun showConnectionTesting() {
        if (!canShowReadouts() || currentState != BaseService.State.Connecting || testing) return
        testing = true
        failed = false
        latency.setTextColor(resources.getColor(R.color.control_bar_text, context.theme))
        latency.setText(R.string.control_bar_testing)
        updateLatencyDescription()
        updateEnabledState()
    }

    /** Negative ms remains an unknown/not-measured compatibility result, not a red failure. */
    fun showLatency(ms: Int) {
        testing = false
        failed = false
        latency.setTextColor(resources.getColor(R.color.control_bar_text, context.theme))
        latency.text = if (!canReceiveMeasurements() || ms < 0) {
            context.getString(R.string.control_bar_latency_unknown)
        } else {
            // Stable ASCII digits, independent of the device's numeric locale.
            context.getString(R.string.control_bar_latency_value, ms.toString())
        }
        updateLatencyDescription()
        updateEnabledState()
    }

    /** Any real service state may fail, including Stopped. Host owns detail and generation checks. */
    fun showFailure(summary: String) {
        testing = false
        if (!canShowReadouts()) {
            resetMeasurements()
        } else {
            failed = true
            // Bound one-line UI text; empty/unknown failures retain the familiar -- ms label.
            latency.text = summary.lineSequence().firstOrNull()?.trim()?.take(32)
                ?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.control_bar_latency_unknown)
            latency.setTextColor(resources.getColor(R.color.control_bar_failure, context.theme))
            updateLatencyDescription()
        }
        updateEnabledState()
    }

    private fun canShowReadouts() = !detached && isShown && windowVisible &&
        windowVisibility == View.VISIBLE

    private fun canReceiveMeasurements() = canShowReadouts() && currentState == BaseService.State.Connected

    private fun requestReadoutAction() {
        if (!canShowReadouts() || testing) return
        if (failed) {
            onError?.invoke() // Both failed readouts show details; never implicitly launch another test.
            return
        }
        requestTest()
    }

    /**
     * Clears rates/latency and the test lock, but preserves the real state and any pending toggle.
     * Use for profile/background/page invalidation after the host cancels its test generation.
     * In particular, leaving for the VPN permission activity must not re-arm a pending start.
     */
    fun clearMeasurements() {
        resetMeasurements()
        updateEnabledState()
    }

    /** Clears measurements and all request locks, without inventing a new service state. */
    fun reset() {
        togglePending = false
        clearMeasurements()
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
        if (detached || !isShown || !windowVisible || windowVisibility != View.VISIBLE || togglePending || isTransitioning()) return
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
        capsule.isEnabled = !detached && isShown && windowVisible &&
            windowVisibility == View.VISIBLE && !togglePending && !isTransitioning()
        val canTest = canShowReadouts() && !testing &&
            (if (failed) onError != null else currentState == BaseService.State.Connected)
        speedBox.isEnabled = canTest
        latencyBox.isEnabled = canTest
    }

    private fun resetMeasurements() {
        testing = false
        failed = false
        latency.setTextColor(resources.getColor(R.color.control_bar_text, context.theme))
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
        sidePressed = false
        sideRipple.state = intArrayOf()
        sideRipple.jumpToCurrentState()
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
            sidePressed = false
            sideRipple.state = intArrayOf()
            sideRipple.jumpToCurrentState()
            clearMeasurements() // Preserve permission togglePending; discard old test presentation.
        } else if (fadeAnimator == null) {
            updateCapsuleIcon()
            updateCapsuleColor(false)
            startConnectingAnimation()
        }
        updateEnabledState() // Becoming visible re-enables testing without a fake state report.
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!ready) return
        val rect = Rect()
        capsule.getDrawingRect(rect)
        offsetDescendantRectToMyCoords(capsule, rect)
        sideMask.setCapsuleBounds(rect)
        sideRipple.invalidateSelf()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (ready) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val rect = Rect()
                sidePressed = listOf(speedBox, latencyBox).any { box ->
                    box.getDrawingRect(rect)
                    offsetDescendantRectToMyCoords(box, rect)
                    box.isEnabled && rect.contains(event.x.toInt(), event.y.toInt())
                }
            }
            if (sidePressed) sideRipple.setHotspot(event.x, event.y)
            updateSideRippleState()
        }
        val handled = super.dispatchTouchEvent(event)
        if (ready && (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            sidePressed = false
            updateSideRippleState()
        }
        return handled
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (ready) updateSideRippleState()
        super.dispatchDraw(canvas)
    }

    private fun updateSideRippleState() {
        val visible = !detached && isShown && windowVisible && windowVisibility == View.VISIBLE
        val pressed = visible && (sidePressed || (speedBox.isEnabled && speedBox.isPressed) ||
            (latencyBox.isEnabled && latencyBox.isPressed))
        val focused = visible && ((speedBox.isEnabled && speedBox.isFocused) ||
            (latencyBox.isEnabled && latencyBox.isFocused))
        sideRipple.state = when {
            pressed -> intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
            focused -> intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
            else -> intArrayOf(android.R.attr.state_enabled)
        }
    }

    /** One rounded outer path minus the actual rounded capsule INCLUDING its antialiased edge. */
    private class CapsuleExclusionMask(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val path = Path()
        private val capsuleBounds = RectF()
        fun setCapsuleBounds(rect: Rect) { capsuleBounds.set(rect); invalidateSelf() }
        override fun draw(canvas: Canvas) {
            path.rewind()
            val inset = density // Keep feedback inside the existing 1dp border.
            path.addRoundRect(RectF(bounds).apply { inset(inset, inset) }, 23f * density,
                23f * density, Path.Direction.CW)
            val hole = RectF(capsuleBounds).apply { inset(-density, -density) }
            path.addRoundRect(hole, hole.height() / 2f, hole.height() / 2f, Path.Direction.CW)
            path.fillType = Path.FillType.EVEN_ODD
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
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
