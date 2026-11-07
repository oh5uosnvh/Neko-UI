package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Looper
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
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
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
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
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
    /** Receives the user's desired state immediately, even while VPN is connecting/stopping. */
    var onToggleIntent: ((Boolean) -> Unit)? = null
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
    /** Persistent power layer; the legacy capsule_icon id intentionally remains on this view. */
    private val capsulePowerLayer: View
    private val capsuleIcon: ImageView
    /** Persistent plane + label layer, independent from the power layer. */
    private val capsulePlaneLayer: View
    private val capsulePlaneContent: View
    private val capsulePlaneIcon: ImageView
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
    // UI intent only. Service state still gates measurements, errors and locks.
    // This starts the HTML transition on the tap, independent of node latency.
    private var requestedConnected: Boolean? = null
    private var detached = false
    private var compactCapsule = false
    private var ready = false
    private var windowVisible = true
    private var failed = false
    private var fadeAnimator: ValueAnimator? = null
    private val night get() = context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** HTML 参考：关闭态灰蓝渐变，连接态主题色渐变（顶部亮 22%）。 */
    private val capsuleOffGradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(if (night) 0xFF3A4351.toInt() else 0xFFC7CFDB.toInt(),
            if (night) 0xFF2E3644.toInt() else 0xFFB5BFCE.toInt()))
    private val capsuleOnGradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0))
    private var capsuleBackground: GradientDrawable = capsuleOffGradient
    private val sideMask = CapsuleExclusionMask(resources.displayMetrics.density)
    private val sideRipple = RippleDrawable(
        ColorStateList.valueOf(themeColor(android.R.attr.colorControlHighlight, R.color.control_bar_border)),
        null, sideMask,
    )
    private var sidePressed = false
    private enum class Presentation { DISCONNECTED, CONNECTED }
    private fun presentation() = if (requestedConnected ?: (currentState == BaseService.State.Connected))
        Presentation.CONNECTED else Presentation.DISCONNECTED

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
        // Real Android's NATIVE text engine may still create a second line for a
        // Spannable even with XML maxLines=1. singleLine locks the layout; clear
        // its implicit END ellipsize afterwards so the spec remains no-ellipsis.
        listOf(speedDown, speedUp).forEach {
            it.setSingleLine(true)
            it.ellipsize = null
            it.setHorizontallyScrolling(true)
        }
        capsule = findViewById(R.id.capsule)
        capsulePowerLayer = findViewById(R.id.capsule_power_layer)
        capsuleIcon = findViewById(R.id.capsule_icon)
        capsulePlaneLayer = findViewById(R.id.capsule_plane_layer)
        capsulePlaneContent = findViewById(R.id.capsule_plane_content)
        capsulePlaneIcon = findViewById(R.id.capsule_plane_icon)
        capsuleLabel = findViewById(R.id.capsule_label)
        latency = findViewById(R.id.latency)
        latencyProgress = findViewById(R.id.latency_progress)
        latency.setSingleLine(true)
        latency.ellipsize = null
        latency.setHorizontallyScrolling(true)

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
        // Keep the persistent THEME capsule; remove only transient press/focus feedback.
        capsule.background = capsuleBackground
        capsule.foreground = null
        // Preserve the readouts' outer-frame feedback, but never paint the toggle target.
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
        if (Looper.myLooper() != Looper.getMainLooper()) { post { changeState(state) }; return }
        val previous = currentState
        val previousPresentation = presentation()
        val wasPending = togglePending
        val requested = requestedConnected
        currentState = state
        // Connecting preserves the already-started visual. Permission cancellation,
        // failures and a final stop roll it back. A duplicate Connected report while
        // a stop request is pending must not undo the immediate stop feedback.
        when (state) {
            BaseService.State.Connecting -> {
                if (onToggleIntent == null) {
                    if (requested != true) requestedConnected = null
                    togglePending = false
                } else if (requested != null) {
                    togglePending = true
                }
            }
            BaseService.State.Stopping -> {
                // Keep the latest user intent visible while the service catches
                // up. This is what makes on/off/on taps reversible mid-transition.
                if (onToggleIntent == null) {
                    requestedConnected = null
                    togglePending = false
                } else if (requested != null) togglePending = true
            }
            BaseService.State.Connected -> {
                if (requested == false && wasPending) {
                    // A stale Connected broadcast must not undo a newer OFF tap.
                    togglePending = true
                } else {
                    requestedConnected = null
                    togglePending = false
                }
            }
            BaseService.State.Stopped -> {
                // Preserve an ON tap made during Stopping until MainActivity
                // dispatches the restart at this safe state. Idle remains the
                // permission-denied/reset path and clears stale intent.
                if (requested == true && onToggleIntent != null) {
                    togglePending = true
                } else {
                    requestedConnected = null
                    togglePending = false
                }
            }
            else -> {
                requestedConnected = null
                togglePending = false
            }
        }
        val changedPresentation = previousPresentation != presentation()
        updateCapsuleSurface()
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
        val density = resources.displayMetrics.density
        for (gradient in listOf(capsuleOffGradient, capsuleOnGradient)) {
            gradient.cornerRadius = 999f * density
        }
        val primary = themeColor(androidx.appcompat.R.attr.colorPrimary, R.color.brand_primary_light)
        val highlight = ColorUtils.blendARGB(primary, Color.WHITE, .22f)
        capsuleOnGradient.setColors(intArrayOf(highlight, primary))
        capsuleBackground = if (presentation() == Presentation.CONNECTED) capsuleOnGradient
            else capsuleOffGradient
        if (capsule.background !== capsuleBackground) capsule.background = capsuleBackground
    }

    /** Update the two persistent layer contents without changing their animation state. */
    private fun updateCapsuleIcon(connected: Boolean = presentation() == Presentation.CONNECTED,
        keepPlaneLabel: Boolean = fadeAnimator != null) {
        updateCapsuleSurface()
        capsuleLabel.setText(if (compactCapsule) {
            if (connected || keepPlaneLabel) R.string.control_bar_connected_compact else R.string.control_bar_disconnected_compact
        } else {
            if (connected || keepPlaneLabel) R.string.control_bar_connected else R.string.control_bar_disconnected
        })
        // Both drawables stay resident. Visibility is controlled by the layer animation;
        // retaining the plane label during a disconnect prevents a mid-flight content pop.
        capsuleLabel.visibility = if (connected || keepPlaneLabel) View.VISIBLE else View.GONE
        val tint = resources.getColor(R.color.control_bar_text, context.theme)
        capsuleIcon.setColorFilter(tint)
        capsulePlaneIcon.setColorFilter(tint)
        capsuleIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_control_power))
        capsulePlaneIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_control_plane))
    }

    private fun shouldAnimateCapsule() = ready && canShowReadouts() &&
        (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    private fun cancelPresentationAnimations() {
        fadeAnimator?.removeAllListeners()
        fadeAnimator?.removeAllUpdateListeners()
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun setLayer(view: View, alpha: Float, scale: Float, rotation: Float) {
        view.alpha = alpha
        view.scaleX = scale
        view.scaleY = scale
        view.rotation = rotation
    }

    private fun settlePresentation() {
        cancelPresentationAnimations()
        val connected = presentation() == Presentation.CONNECTED
        setLayer(capsulePowerLayer, if (connected) 0f else 1f,
            if (connected) .4f else 1f, if (connected) -90f else 0f)
        setLayer(capsulePlaneLayer, if (connected) 1f else 0f,
            if (connected) 1f else .4f, if (connected) 0f else 90f)
        capsulePowerLayer.visibility = View.VISIBLE
        capsulePlaneLayer.visibility = View.VISIBLE
        capsulePlaneIcon.translationY = 0f
        capsulePlaneIcon.rotation = 0f
        findViewById<View>(R.id.capsule_pair).alpha = 1f
        updateCapsuleIcon(connected)
    }

    private fun overshoot(t: Float): Float =
        android.view.animation.OvershootInterpolator(1.56f).getInterpolation(t.coerceIn(0f, 1f))

    /**
     * HTML .toggle presentation: power and plane are independent persistent layers.
     * Alpha reaches its target in .3s, transforms in .4s, and the plane takeoff settles
     * over .55s.  Starting values are sampled after cancelling the previous animator, so
     * reversing at any point continues from the rendered attributes rather than resetting.
     */
    private fun animatePresentation() {
        val connected = presentation() == Presentation.CONNECTED
        val pair = findViewById<View>(R.id.capsule_pair)
        val density = resources.displayMetrics.density
        val takeoff = 4f * density
        val startPower = floatArrayOf(capsulePowerLayer.alpha, capsulePowerLayer.scaleX, capsulePowerLayer.rotation)
        val startPlane = floatArrayOf(capsulePlaneLayer.alpha, capsulePlaneLayer.scaleX, capsulePlaneLayer.rotation)
        val startY = capsulePlaneIcon.translationY
        val startTakeoffRotation = capsulePlaneIcon.rotation
        cancelPresentationAnimations()
        // A fresh connection starts the same takeoff path as the HTML keyframe.  On a
        // reversal, startY is retained from the old flight instead.
        val planeStartY = if (connected && startPlane[0] < .001f && startY == 0f) takeoff else startY
        // A disconnect/Connecting target must not present a connected label, even while
        // the plane layer is finishing its independent fade-out.
        // Keep plane content resident through either direction; only the settled
        // disconnect hides its label. This prevents a content pop while the layer fades.
        updateCapsuleIcon(connected, keepPlaneLabel = true)
        val powerTarget = floatArrayOf(if (connected) 0f else 1f, if (connected) .4f else 1f,
            if (connected) -90f else 0f)
        val planeTarget = floatArrayOf(if (connected) 1f else 0f, if (connected) 1f else .4f,
            if (connected) 0f else 90f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val alphaT = (progress / (.3f / .55f)).coerceIn(0f, 1f)
                val transformT = overshoot((progress / (.4f / .55f)).coerceIn(0f, 1f))
                fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
                setLayer(capsulePowerLayer,
                    lerp(startPower[0], powerTarget[0], alphaT),
                    lerp(startPower[1], powerTarget[1], transformT),
                    lerp(startPower[2], powerTarget[2], transformT))
                setLayer(capsulePlaneLayer,
                    lerp(startPlane[0], planeTarget[0], alphaT),
                    lerp(startPlane[1], planeTarget[1], transformT),
                    lerp(startPlane[2], planeTarget[2], transformT))
                val takeoffT = (progress / 1f).coerceIn(0f, 1f)
                if (connected) {
                    // 4dp -> -2dp at 45%, then -> 0dp, matching @keyframes takeoff.
                    capsulePlaneIcon.translationY = if (takeoffT < .45f)
                        lerp(planeStartY, -2f * density, takeoffT / .45f)
                    else lerp(-2f * density, 0f, (takeoffT - .45f) / .55f)
                    capsulePlaneIcon.rotation = if (takeoffT < .45f)
                        lerp(startTakeoffRotation, -9f, takeoffT / .45f)
                    else lerp(-9f, 0f, (takeoffT - .45f) / .55f)
                } else {
                    capsulePlaneIcon.translationY = lerp(startY, 0f, takeoffT)
                    capsulePlaneIcon.rotation = lerp(startTakeoffRotation, 0f, takeoffT)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fadeAnimator !== animation) return
                    fadeAnimator = null
                    setLayer(capsulePowerLayer, powerTarget[0], powerTarget[1], powerTarget[2])
                    setLayer(capsulePlaneLayer, planeTarget[0], planeTarget[1], planeTarget[2])
                    capsulePlaneIcon.translationY = 0f
                    capsulePlaneIcon.rotation = 0f
                    updateCapsuleIcon(connected)
                    pair.alpha = 1f
                }
            })
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
            // The toggle keeps the HTML-fixed 118x44dp capsule; only readout weights flex.
            speedBox.setPaddingRelative(speedBox.paddingStart, speedBox.paddingTop,
                ((if (compact) 0 else 4) * density).toInt(), speedBox.paddingBottom)
            latencyBox.setPaddingRelative(((if (compact) 0 else 4) * density).toInt(),
                latencyBox.paddingTop, (4 * density).toInt(), latencyBox.paddingBottom)
        }
        // Re-apply on every measure: NATIVE TextView can restore its autosize
        // choice during a later exact-width pass. In the extreme compact case,
        // preserve the 8sp readable height but use a modest horizontal scale so
        // the long speed token remains one unellipsized line.
        if (compact) {
            listOf(speedDown, speedUp).forEach {
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                it.setTextScaleX(.80f)
                TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    it, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            }
            latency.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            latency.setTextScaleX(.85f)
            TextViewCompat.setAutoSizeTextTypeWithDefaults(
                latency, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
        } else {
            listOf(speedDown, speedUp).forEach {
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                it.setTextScaleX(1f)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    it, 8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            latency.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            latency.setTextScaleX(1f)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                latency, 8, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Preference/probe callbacks can fire on Default workers; views and animators are main-only. */
    private fun onControlThread(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else post(block)
    }

    fun updateSpeed(txRate: Long, rxRate: Long) = onControlThread {
        if (!canReceiveMeasurements()) return@onControlThread
        speedDown.text = spannedSpeed(context.getString(R.string.control_bar_speed_down, fmt(rxRate)))
        speedUp.text = spannedSpeed(context.getString(R.string.control_bar_speed_up, fmt(txRate)))
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
        requestedConnected = null
        settlePresentation()
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
        if (!canShowReadouts()) return
        if (onToggleIntent == null && onToggle == null) return
        // Keep the old callback contract safe for embedders. MainActivity uses
        // onToggleIntent, which deliberately opts into reversible rapid taps.
        if (onToggleIntent == null && (togglePending || isTransitioning())) return
        val desired = !(requestedConnected ?: (currentState == BaseService.State.Connected))
        // The visual is an immediate user-intent presentation. It must not wait
        // for VPN broadcasts, so repeated taps alternate it smoothly.
        requestedConnected = desired
        togglePending = true
        if (shouldAnimateCapsule()) animatePresentation() else settlePresentation()
        updateEnabledState()
        try {
            onToggleIntent?.invoke(desired) ?: onToggle?.invoke()
        } catch (error: Exception) {
            togglePending = false
            requestedConnected = null
            if (shouldAnimateCapsule()) animatePresentation() else settlePresentation()
            updateEnabledState()
            throw error
        }
    }

    private fun isTransitioning() = currentState == BaseService.State.Connecting ||
        currentState == BaseService.State.Stopping

    private fun updateEnabledState() {
        // MainActivity opts into reversible rapid taps through onToggleIntent.
        // Keep the legacy callback contract locked for embedders that have not
        // adopted the intent API yet.
        capsule.isEnabled = canShowReadouts() &&
            (onToggleIntent != null || (!togglePending && !isTransitioning()))
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
        // 未连接一律中性色；绿色延迟只在已连接的读数上出现（spec 7.5）。
        latency.setTextColor(resources.getColor(
            if (currentState == BaseService.State.Connected) R.color.latency_green
            else R.color.control_bar_text, context.theme))
        val unknown = context.getString(R.string.control_bar_speed_unknown)
        speedDown.text = spannedSpeed(context.getString(R.string.control_bar_speed_down, unknown))
        speedUp.text = spannedSpeed(context.getString(R.string.control_bar_speed_up, unknown))
        latency.setText(R.string.control_bar_latency_unknown)
        updateSpeedDescription()
        updateLatencyDescription()
    }

    /** 数字为主色，单位 kB/s 更浅更小（HTML 参考）；文本内容保持不变供无障碍使用。 */
    private fun spannedSpeed(composed: CharSequence): CharSequence {
        val text = composed.toString()
        val unitStart = text.lastIndexOf(' ') + 1
        if (unitStart <= 0 || unitStart >= text.length) return text
        val spannable = android.text.SpannableString(text)
        val unitColor = themeColor(android.R.attr.textColorSecondary, R.color.control_bar_text)
        spannable.setSpan(android.text.style.ForegroundColorSpan(unitColor), unitStart, text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(android.text.style.RelativeSizeSpan(.82f), unitStart, text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
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
        pressAnimator?.removeAllListeners()
        pressAnimator?.removeAllUpdateListeners()
        pressAnimator?.cancel()
        pressAnimator = null
        pressTarget = false
        val pair = findViewById<View>(R.id.capsule_pair)
        pair.alpha = 1f
        setLayer(capsulePowerLayer, if (presentation() == Presentation.CONNECTED) 0f else 1f,
            if (presentation() == Presentation.CONNECTED) .4f else 1f,
            if (presentation() == Presentation.CONNECTED) -90f else 0f)
        setLayer(capsulePlaneLayer, if (presentation() == Presentation.CONNECTED) 1f else 0f,
            if (presentation() == Presentation.CONNECTED) 1f else .4f,
            if (presentation() == Presentation.CONNECTED) 0f else 90f)
        capsulePlaneIcon.translationY = 0f
        capsulePlaneIcon.rotation = 0f
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
        if (!canShowReadouts()) {
            settlePresentation() // Also restores scale/rotation/translation mid-states.
            sidePressed = false
            sideRipple.state = intArrayOf()
            sideRipple.jumpToCurrentState()
            clearMeasurements() // Preserve permission togglePending; discard old test presentation.
        } else if (fadeAnimator == null) {
            updateCapsuleIcon()
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
                capsule.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(capsule, rect)
                val inCapsule = rect.contains(event.x.toInt(), event.y.toInt())
                // The side surface includes the top/bottom bands around the
                // center capsule, not only the two readout child bounds.
                // Those bands must receive the same pressed feedback.
                sidePressed = canShowReadouts() && !inCapsule
                pressCapsule(inCapsule && capsule.isEnabled)
            }
            if (sidePressed) sideRipple.setHotspot(event.x, event.y)
            updateSideRippleState()
        }
        val handled = super.dispatchTouchEvent(event)
        if (ready && (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            sidePressed = false
            speedBox.isPressed = false
            latencyBox.isPressed = false
            speedBox.cancelLongPress()
            latencyBox.cancelLongPress()
            pressCapsule(false)
            updateSideRippleState()
        }
        return handled
    }

    /**
     * HTML 按压手感：按下整体缩到 0.90（120ms 快速），松手带过冲回弹（300ms）。
     * 只动 transform，不改变胶囊颜色，也不在胶囊外层叠加 ripple 边框。
     */
    private fun pressCapsule(pressed: Boolean) {
        if (!ready || pressed == pressTarget) return
        pressTarget = pressed
        pressAnimator?.removeAllListeners()
        pressAnimator?.removeAllUpdateListeners()
        pressAnimator?.cancel()
        capsule.pivotX = capsule.width / 2f
        capsule.pivotY = capsule.height / 2f
        val from = capsule.scaleX
        val to = if (pressed) .90f else 1f
        pressAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (pressed) 120L else 300L
            interpolator = if (pressed) DecelerateInterpolator()
                else android.view.animation.OvershootInterpolator(2.2f)
            addUpdateListener {
                val value = it.animatedValue as Float
                capsule.scaleX = value
                capsule.scaleY = value
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pressAnimator = null
                    capsule.scaleX = to
                    capsule.scaleY = to
                }
            })
            start()
        }
    }
    private var pressAnimator: ValueAnimator? = null
    private var pressTarget = false

    override fun dispatchDraw(canvas: Canvas) {
        if (ready) updateSideRippleState()
        super.dispatchDraw(canvas)
    }

    private fun updateSideRippleState() {
        val visible = canShowReadouts()
        val pressed = visible && (sidePressed || (speedBox.isEnabled && speedBox.isPressed) ||
            (latencyBox.isEnabled && latencyBox.isPressed))
        val focused = visible && !isInTouchMode && ((speedBox.isEnabled && speedBox.isFocused) ||
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
            // The frame no longer has a stroke: keep only a hairline inset for antialiasing.
            val inset = density * .5f
            path.addRoundRect(RectF(bounds).apply { inset(inset, inset) }, 29.5f * density,
                29.5f * density, Path.Direction.CW)
            // Exclude only the actual central capsule. The area above and below
            // it belongs to the side control surface and must receive the same
            // pressed/ripple feedback as the left and right readout areas.
            val hole = RectF(capsuleBounds)
            path.op(Path().apply {
                addRoundRect(hole, 999f * density, 999f * density, Path.Direction.CW)
            }, Path.Op.DIFFERENCE)
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
