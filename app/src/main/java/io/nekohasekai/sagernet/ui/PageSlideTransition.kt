package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import io.nekohasekai.sagernet.R

/** FragmentManager owns show/hide/removal; this owns only horizontal presentation. */
internal class PageSlideTransition(private val onSettled: () -> Unit = {}) {
    private data class Page(val view: View, val layerType: Int, var layered: Boolean = false)
    private val running = LinkedHashMap<PageAnimator, Page>()
    private var request: Request? = null
    val isRunning: Boolean get() = running.isNotEmpty() || request?.waiting == true

    /**
     * Arm before commit, but do not start a clock during inflation. Both FragmentManager animators
     * join the same frame barrier, including the outgoing page whose view FM must keep alive.
     */
    fun prepare(host: View, isValid: () -> Boolean, isReady: () -> Boolean,
                accepts: (View) -> Boolean) {
        cancel()
        val next = Request(host, isValid, isReady, accepts)
        request = next
        next.gate.await()
    }

    private inner class Request(host: View, val valid: () -> Boolean,
                                ready: () -> Boolean, val accepts: (View) -> Boolean) {
        var waiting = true
        var cancelled = false
        val pending = LinkedHashSet<PageAnimator>()
        val gate = NavigationFrameGate(host,
            { !cancelled && valid() },
            { pending.isNotEmpty() && ready() },
            {
                waiting = false
                // Record the expensive first display lists before the animation's play time starts.
                // Only two visible pages get temporary layers, never the entire cached hierarchy.
                running.values.forEach { page ->
                    if (page.view.isHardwareAccelerated && page.view.isAttachedToWindow &&
                        page.layerType == View.LAYER_TYPE_NONE) {
                        page.layered = true
                        page.view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        page.view.buildLayer()
                    }
                }
                pending.toList().also { pending.clear() }.forEach { it.play() }
            },
            { this@PageSlideTransition.cancel() })

        fun enqueue(animator: PageAnimator) {
            when {
                cancelled || !valid() -> animator.end()
                waiting -> pending.add(animator)
                else -> animator.play()
            }
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            waiting = false
            gate.cancel()
            pending.clear()
            this@PageSlideTransition.cancelAnimators()
        }
    }

    private inner class PageAnimator(private val owner: Request?) : ValueAnimator() {
        override fun end() {
            owner?.pending?.remove(this)
            super.end()
            settle(this)
        }
        override fun cancel() {
            // FragmentManager may force-complete a postponed effect before start() ran.
            removeAllUpdateListeners()
            super.cancel()
            if (this in running) end()
            settle(this)
        }
        override fun start() {
            if (this !in running) {
                // A framework callback can arrive after save/stop. Complete its effect without
                // reviving a stale translation or leaving FragmentManager waiting for onEnd.
                super.start()
                super.end()
            } else if (owner != null) owner.enqueue(this) else play()
        }
        fun play() { if (this in running) super.start() }
    }

    fun create(view: View, enter: Boolean, nextAnim: Int): Animator? {
        val forward = when (nextAnim) {
            R.animator.page_slide_forward -> true
            R.animator.page_slide_backward -> false
            else -> return null // Startup and restored pages are deliberately motionless.
        }
        val owner = request
        if (owner != null && (owner.cancelled || !owner.valid() || !owner.accepts(view))) {
            // Do not fall back to the XML marker's 260ms animator for a stale transaction.
            return ValueAnimator.ofFloat(0f, 0f).apply { duration = 0L }
        }
        val width = ((view.parent as? View)?.width ?: 0).takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        val direction = if (forward) 1f else -1f
        val from = if (enter) direction * width else 0f
        val to = if (enter) 0f else -direction * width
        view.alpha = 1f
        view.translationX = from
        return PageAnimator(owner).apply {
            setFloatValues(from, to)
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            running[this] = Page(view, view.layerType)
            addUpdateListener { view.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = settle(this@apply)
            })
        }
    }

    private fun settle(animation: PageAnimator) {
        val page = running.remove(animation) ?: return
        animation.removeAllUpdateListeners()
        val view = page.view
        view.translationX = 0f
        view.alpha = 1f
        if (page.layered) view.setLayerType(page.layerType, null)
        view.postInvalidateOnAnimation()
        if (running.isEmpty()) {
            request?.gate?.cancel()
            request?.waiting = false
            onSettled()
        }
    }

    private fun cancelAnimators() {
        val hadWork = isRunning
        running.keys.toList().forEach {
            it.removeAllUpdateListeners()
            // end(), unlike cancel(), also completes a not-yet-started FM special effect.
            it.end()
            settle(it)
        }
        if (hadWork && running.isEmpty()) request?.waiting = false
    }

    fun cancel() {
        val waitingWithoutAnimator = request?.waiting == true && running.isEmpty()
        request?.cancel()
        cancelAnimators()
        if (waitingWithoutAnimator) onSettled()
    }

    companion object { const val DURATION_MS = 260L }
}
