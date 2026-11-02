package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlin.math.abs

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
    }

    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    @Suppress("unused")
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate

    private var scrollHidden = false
    private var scrollDirection = 0 // 1 = hide (dy>0), -1 = show (dy<0), 0 = none
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
            updateHideOnScroll()
        }

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver || !shouldShow()) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun slideUp(child: BottomAppBar) {
            slideUp(child, true)
        }

        override fun slideUp(child: BottomAppBar, animate: Boolean) {
            if (!shouldShow()) { commitHidden(); return }
            super.slideUp(child, animate)
        }

        override fun onLayoutChild(parent: CoordinatorLayout, child: BottomAppBar, layoutDirection: Int): Boolean {
            val handled = super.onLayoutChild(parent, child, layoutDirection)
            // Material updates its hidden height after child.onLayout; enforce our gate afterwards.
            if (!shouldShow()) commitHidden()
            return handled
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!shouldShow()) {
            pendingTransition = null
            commitHidden()
            return
        }
        val transition = pendingTransition
        if (transition != null) {
            pendingTransition = null
            if (transition == Transition.HideImmediate) commitHidden()
            else commitVisible(animated = transition == Transition.ShowAnimated)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        if (!shouldShow()) commitHidden()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateHideOnScroll() {
        hideOnScroll =
            !useExternalScrollDriver && allowShow && currentState == BaseService.State.Connected
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    fun onListScrolled(dy: Int) {
        if (!useExternalScrollDriver || !shouldShow() || dy == 0) return

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        if (wantHidden) performHide() else performShow()
    }

    fun syncMainControls(
        showControls: Boolean,
        state: BaseService.State,
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        currentState = state
        allowShow = showControls
        when {
            !showControls || state != BaseService.State.Connected -> applyTransition(Transition.HideImmediate)

            showWhenConnected -> applyTransition(
                if (animate) Transition.ShowAnimated else Transition.ShowImmediate
            )
            alpha == 0f && isLaidOut -> alpha = 1f
        }
    }

    private fun applyTransition(transition: Transition) {
        if (transition == Transition.HideImmediate || !shouldShow()) {
            pendingTransition = null
            commitHidden()
            return
        }
        if (!isLaidOut || height == 0) {
            pendingTransition = transition
            alpha = 0f
            return
        }
        pendingTransition = null
        commitVisible(animated = transition == Transition.ShowAnimated)
    }

    private fun commitVisible(animated: Boolean) {
        if (!shouldShow()) { commitHidden(); return }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        isClickable = true
        alpha = 1f
        scrollHidden = false
        resetScrollDriverState()
        if (animated) {
            performShow()
        } else {
            getBehavior().slideUp(this, false)
            animate().cancel()
            translationY = 0f
        }
    }

    private fun commitHidden() {
        // Alpha gates the very first frame; preserve VISIBLE as the FAB's layout anchor.
        alpha = 0f
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        scrollHidden = true
        resetScrollDriverState()
        animate().cancel()
        clearAnimation()
        getBehavior().slideDown(this, false)
        animate().cancel()
        val margin = (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        translationY = (height + margin).toFloat()
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState(state)
        if (!shouldShow()) { pendingTransition = null; commitHidden() }
    }

    override fun onDetachedFromWindow() {
        pendingTransition = null
        animate().cancel()
        super.onDetachedFromWindow()
    }

    fun changeState(state: BaseService.State) {
        currentState = state
        updateHideOnScroll()
        if (state == BaseService.State.Connected) {
            setStatus(context.getText(R.string.vpn_connected))
        } else {
            pendingTransition = null
            commitHidden()
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
