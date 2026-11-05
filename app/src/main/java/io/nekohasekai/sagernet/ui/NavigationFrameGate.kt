package io.nekohasekai.sagernet.ui

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.FragmentContainerView
import androidx.recyclerview.widget.RecyclerView

/**
 * A frame barrier, not an animation delay. Fragment commit does not mean that nested fragment
 * transactions, preference binding or the first layout/draw have finished. Keep the two page
 * translations at their start positions until two clean traversals have actually been drawn.
 * Only the requested page is inspected; no page inflation or database work happens here.
 */
internal class NavigationFrameGate(
    private val host: View,
    private val isValid: () -> Boolean,
    private val isReady: () -> Boolean,
    private val onReady: () -> Unit,
    private val onAborted: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var observer: ViewTreeObserver? = null
    private var cleanFrames = 0
    private var active = false
    private var posted = false
    private val abort = Runnable { finish(false) }
    private val start = Runnable {
        posted = false
        if (!active) return@Runnable
        if (!host.isAttachedToWindow || !isValid()) {
            finish(false)
        } else if (isReady()) {
            finish(true)
        } else {
            // A child commit/adapter update landed after pre-draw. Never start into that layout.
            cleanFrames = 0
            host.postInvalidateOnAnimation()
        }
    }

    fun await() {
        if (active) return
        if (!host.isAttachedToWindow || !isValid()) { onAborted(); return }
        active = true
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        host.addOnAttachStateChangeListener(this)
        // Safety valve for a destroyed/failed child, NOT the normal animation start condition.
        host.postDelayed(abort, MAX_WAIT_MS)
        host.postInvalidateOnAnimation()
    }

    override fun onPreDraw(): Boolean {
        if (!active || posted) return true
        if (!isValid()) {
            finish(false)
        } else {
            cleanFrames = if (isReady()) cleanFrames + 1 else 0
            if (cleanFrames >= CLEAN_FRAMES) {
                posted = true
                // The current traversal must draw before an animator's play time can advance.
                host.postOnAnimation(start)
            } else host.postInvalidateOnAnimation()
        }
        return true // Never block the outgoing page/window from drawing while preparing.
    }

    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) { finish(false) }

    private fun finish(ready: Boolean) {
        if (!active) return
        cancel()
        if (ready) onReady() else onAborted()
    }

    fun cancel() {
        active = false
        posted = false
        cleanFrames = 0
        host.removeCallbacks(start)
        host.removeCallbacks(abort)
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        host.removeOnAttachStateChangeListener(this)
    }

    companion object {
        internal const val CLEAN_FRAMES = 2
        internal const val MAX_WAIT_MS = 2_000L

        /** Includes nested settings/about containers and RecyclerView's pending first bind. */
        internal fun isPageReady(view: View?): Boolean = view != null &&
            view.isAttachedToWindow && view.width > 0 && view.height > 0 &&
            view.isLaidOut && treeReady(view)

        private fun treeReady(view: View): Boolean {
            if (view.visibility == View.GONE) return true
            if (view.isLayoutRequested) return false
            if (view is FragmentContainerView && view.childCount == 0) return false
            if (view is RecyclerView && (view.adapter == null || view.isComputingLayout ||
                    view.hasPendingAdapterUpdates())) return false
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (!treeReady(view.getChildAt(index))) return false
                }
            }
            return true
        }
    }
}
