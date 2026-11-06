package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FlClash 0.9.2-MG-pre2 style popup motion, shared by EVERY list menu entrance
 * and exit (spec: no entrance may be a one-frame popup while another animates).
 *
 * Open: 450ms under-damped spring (mass 1, stiffness 400, damping ratio 0.754).
 * The surface grows out of the anchor corner with a slight natural overshoot.
 * Close: 200ms easeOut shrink 1.0 -> 0.8 plus fade; the window is removed only
 * after the animation completes, never in one frame.
 */
object PopupEntrance {
    const val OPEN_DURATION_MS = 450L
    const val CLOSE_DURATION_MS = 200L
    const val DISMISS_DURATION_MS = 500L

    private const val STIFFNESS = 400.0
    private const val DAMPING_RATIO = 0.754

    /** Under-damped step response of mass/spring/damper, t in seconds, 0..1 (may overshoot). */
    fun springProgress(t: Double): Double {
        val natural = sqrt(STIFFNESS)
        val damped = natural * sqrt(1.0 - DAMPING_RATIO * DAMPING_RATIO)
        val decay = exp(-DAMPING_RATIO * natural * t)
        val oscillation = cos(damped * t) +
            DAMPING_RATIO * natural / damped * sin(damped * t)
        return 1.0 - decay * oscillation
    }

    fun openAnimator(view: View, onEnd: () -> Unit = {}): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_DURATION_MS
            addUpdateListener {
                val p = springProgress((it.currentPlayTime) / 1000.0).toFloat()
                view.alpha = (p * 1.6f).coerceAtMost(1f)
                val scale = (0.4f + 0.6f * p).coerceIn(0.4f, 1.12f)
                view.scaleX = scale
                view.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                private var fired = false
                override fun onAnimationEnd(animation: Animator) {
                    animation.removeListener(this)
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    if (!fired) { fired = true; onEnd() }
                }
            })
        }

    /**
     * Pivot the surface at the anchor corner: the menu visibly grows out of the
     * trigger instead of scaling from its own center or the screen top.
     */
    fun anchorPivot(surface: View, anchor: View, aboveAnchor: Boolean) {
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val surfaceLocation = IntArray(2)
        surface.getLocationOnScreen(surfaceLocation)
        val centerX = location[0] + anchor.width / 2f - surfaceLocation[0]
        surface.pivotX = centerX.coerceIn(0f, surface.width.coerceAtLeast(1).toFloat())
        surface.pivotY = if (aboveAnchor) surface.height.toFloat() else 0f
    }

    /** Shared entrance for every popup surface. Cancels any running exit first. */
    fun animate(view: View, aboveAnchor: Boolean = false, anchor: View? = null) {
        if (!view.isAttachedToWindow) {
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            return
        }
        if (anchor != null) anchorPivot(view, anchor, aboveAnchor)
        else {
            view.pivotX = view.width / 2f
            view.pivotY = if (aboveAnchor) view.height.toFloat() else 0f
        }
        (view.tag as? ValueAnimator)?.cancel()
        val animator = openAnimator(view) { view.tag = null }
        view.tag = animator
        animator.start()
    }

    /** Shrink + fade out; the window removal happens in [onEnd] after the animation. */
    fun exit(view: View, aboveAnchor: Boolean = false, anchor: View? = null, onEnd: () -> Unit) {
        if (!view.isAttachedToWindow) { onEnd(); return }
        if (anchor != null) anchorPivot(view, anchor, aboveAnchor)
        else {
            view.pivotX = view.width / 2f
            view.pivotY = if (aboveAnchor) view.height.toFloat() else 0f
        }
        (view.tag as? ValueAnimator)?.cancel()
        view.isEnabled = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOSE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                view.alpha = 1f - f
                view.scaleX = 1f - .2f * f
                view.scaleY = view.scaleX
            }
            addListener(object : AnimatorListenerAdapter() {
                private var fired = false
                override fun onAnimationEnd(animation: Animator) {
                    animation.removeListener(this)
                    view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.isEnabled = true
                    if (!fired) { fired = true; onEnd() }
                }
            })
            start()
        }
        view.tag = animator
    }

    /** Escalating dismiss for menus without a clear trigger snapshot (spec 3.3). */
    fun dismissWindow(surface: View, aboveAnchor: Boolean = false, after: () -> Unit) {
        exit(surface, aboveAnchor, null) { after() }
    }
}
