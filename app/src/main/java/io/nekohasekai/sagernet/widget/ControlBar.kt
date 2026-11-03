package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
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
        speedBox.setOnClickListener { requestTest() }
        latencyBox.setOnClickListener { requestTest() }
        capsule.setOnClickListener { requestToggle() }
        changeState(currentState)
    }

    /** Only the real Connected state selects the dark capsule and white Telegram icon. */
    fun changeState(state: BaseService.State) {
        val wasConnected = currentState == BaseService.State.Connected
        currentState = state
        togglePending = false // Includes an authoritative same-state permission-cancel response.
        val connected = state == BaseService.State.Connected
        capsule.isSelected = connected
        capsule.contentDescription = context.getString(
            if (connected) R.string.control_bar_disconnect_action else R.string.control_bar_connect_action
        )
        updateCapsuleIcon()
        // Duplicate Connected notifications must not erase a live test or its latest result.
        if (!connected || !wasConnected) resetMeasurements()
        updateEnabledState()
    }

    private fun updateCapsuleIcon() {
        val connected = currentState == BaseService.State.Connected
        capsuleLabel.setText(if (compactCapsule) {
            if (connected) R.string.control_bar_connected_compact else R.string.control_bar_disconnected_compact
        } else {
            if (connected) R.string.control_bar_connected else R.string.control_bar_disconnected
        })
        capsuleIcon.setImageDrawable(AppCompatResources.getDrawable(context,
            if (connected) R.drawable.ic_control_plane else R.drawable.ic_control_power))
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
        if (detached || currentState != BaseService.State.Connected) return
        speedDown.text = context.getString(R.string.control_bar_speed_down, fmt(rxRate))
        speedUp.text = context.getString(R.string.control_bar_speed_up, fmt(txRate))
        updateSpeedDescription()
    }

    /** Idempotent; the click handler also acquires this lock before calling the host. */
    fun showTesting() {
        if (detached || currentState != BaseService.State.Connected || testing) return
        testing = true
        latency.setText(R.string.control_bar_testing)
        updateLatencyDescription()
        updateEnabledState()
    }

    /** Negative ms means failure/not measured. Every completion releases the test lock. */
    fun showLatency(ms: Int) {
        testing = false
        latency.text = if (detached || currentState != BaseService.State.Connected || ms < 0) {
            context.getString(R.string.control_bar_latency_unknown)
        } else {
            // Stable ASCII digits, independent of the device's numeric locale.
            context.getString(R.string.control_bar_latency_value, ms.toString())
        }
        updateLatencyDescription()
        updateEnabledState()
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
        if (detached || currentState != BaseService.State.Connected || testing) return
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
        if (detached || togglePending || isTransitioning()) return
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
        capsule.isEnabled = !detached && !togglePending && !isTransitioning()
        val canTest = !detached && currentState == BaseService.State.Connected && !testing
        speedBox.isEnabled = canTest
        latencyBox.isEnabled = canTest
    }

    private fun resetMeasurements() {
        testing = false
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
            R.string.control_bar_latency_description, latency.text
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        updateEnabledState()
    }

    override fun onDetachedFromWindow() {
        detached = true
        reset() // No timers/jobs/animations, and no test state survives removal/destruction.
        super.onDetachedFromWindow()
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
