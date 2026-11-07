package io.nekohasekai.sagernet.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.LatencyTestStrip
import kotlin.math.max

/**
 * Owns the single latency strip attached to the configuration foreground.
 * The foreground is a sibling of the pager, so this never changes pager padding,
 * adapter range, card positions, or scrolling.  The host is deliberately not
 * TitleBarLayout: TitleBarLayout only lays out recognised direct children.
 */
class LatencyTestOverlay(
    private val root: View,
    private val onCancel: () -> Unit,
) {
    private val host: ViewGroup? =
        (root.findViewById<View>(R.id.group_list_foreground) as? ViewGroup)
            ?: (root as? ViewGroup)
    private val strip = LatencyTestStrip(root.context)
    private var released = false
    private var globalObserver: ViewTreeObserverHandle? = null
    private var lastWidth = -1
    private var lastTop = -1
    private var lastHeight = -1
    private var lastMargin = -1
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncGeometry() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (!released) installGeometryObserver() }
        override fun onViewDetachedFromWindow(v: View) { globalObserver?.remove(); globalObserver = null }
    }

    init {
        strip.setCancelListener { if (!released) onCancel() }
        host?.addView(strip, ViewGroup.LayoutParams(1, 1))
        host?.addOnLayoutChangeListener(layoutListener)
        host?.addOnAttachStateChangeListener(attachListener)
        installGeometryObserver()
        syncGeometry()
    }

    /** Real test callback: updates only the strip, never the node list. */
    fun running(done: Int, total: Int, failed: Int) {
        if (!released) { syncGeometry(); strip.updateRunning(done, total, failed) }
    }

    /** Keeps the completed/interrupted result under the same strip dimensions. */
    fun completed(available: Int, failed: Int, interrupted: Boolean = false) {
        if (!released) { syncGeometry(); strip.showResult(available, failed, interrupted) }
    }

    /** Shows a same-sized empty-state strip and clears old counters. */
    fun empty(message: CharSequence) {
        if (!released) { syncGeometry(); strip.showEmpty(message) }
    }

    fun release() {
        if (released) return
        released = true
        host?.removeOnLayoutChangeListener(layoutListener)
        host?.removeOnAttachStateChangeListener(attachListener)
        globalObserver?.remove()
        globalObserver = null
        strip.release()
        if (strip.parent === host) host?.removeView(strip)
    }

    /** Useful to the real fragment when its tabs become visible after setup. */
    fun refresh() { if (!released) syncGeometry() }

    internal val stripForTests: LatencyTestStrip get() = strip

    private fun syncGeometry() {
        val container = host ?: return
        if (released || container.width <= 0) return
        val tabs = root.findViewById<View>(R.id.group_tab)
        val tabBottom = tabs?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }?.bottom
        val y = tabBottom ?: fallbackY()
        val pill = tabs?.let { findPill(it) ?: findTextView(it) }
        val pillHeight = pill?.height?.takeIf { it > 0 } ?: tabs?.height?.takeIf { it > 0 }
        val actualHeight = pillHeight ?: fallbackHeight()
        val margin = max(1, (16f * root.resources.displayMetrics.density).toInt())
        val available = (container.width - 2 * margin).coerceAtLeast(1)
        val top = y.coerceAtLeast(0)
        if (actualHeight != lastHeight) strip.copyPillStyle(pill, actualHeight)
        if (available != lastWidth) strip.setAvailableWidth(available)
        if (available != lastWidth || top != lastTop || actualHeight != lastHeight || margin != lastMargin) {
            val lp = (strip.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Reserve a fixed canvas once. The painted strip width animates
            // internally, so progress digits never remeasure the pager siblings.
            lp.width = available
            lp.height = actualHeight
            lp.leftMargin = margin
            lp.rightMargin = margin
            lp.topMargin = top
            lp.bottomMargin = 0
            lp.gravity = Gravity.TOP or Gravity.START
            strip.layoutParams = lp
            lastWidth = available
            lastTop = top
            lastHeight = actualHeight
            lastMargin = margin
        }
        strip.translationZ = 1f
    }

    private fun fallbackY(): Int {
        val appbar = root.findViewById<View>(R.id.appbar)
        if (appbar != null && appbar.visibility != View.GONE) {
            val location = IntArray(2); appbar.getLocationInWindow(location)
            val hostLocation = IntArray(2); (host ?: root).getLocationInWindow(hostLocation)
            return (location[1] + appbar.height - hostLocation[1]).coerceAtLeast(0)
        }
        return fallbackHeight()
    }

    private fun fallbackHeight(): Int {
        val dm = root.resources.displayMetrics
        return (20f * dm.scaledDensity + .5f).toInt().coerceAtLeast(1)
    }

    private fun findPill(view: View): TextView? {
        if (view is io.nekohasekai.sagernet.widget.GroupTabPill) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findPill(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findTextView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun installGeometryObserver() {
        globalObserver?.remove()
        val observer = root.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { syncGeometry() }
        observer.addOnGlobalLayoutListener(listener)
        globalObserver = ViewTreeObserverHandle(observer, listener)
    }

    private class ViewTreeObserverHandle(
        private val observer: android.view.ViewTreeObserver,
        private val listener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
    ) {
        fun remove() { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }
}
