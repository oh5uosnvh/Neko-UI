package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.animation.DecelerateInterpolator
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one motion owner for popup surfaces.
 *
 * A session is kept outside View.tag so application tags remain untouched.  It also
 * deliberately waits for both attachment and a real layout: PopupWindow can attach a
 * zero-sized content view for one frame, which used to produce a centre pivot and a
 * visible jump.  Detaching either the surface or its anchor cancels the session without
 * running an obsolete completion callback.
 */
object PopupEntrance {
    const val OPEN_DURATION_MS = 450L
    const val CLOSE_DURATION_MS = 200L
    const val DISMISS_DURATION_MS = 500L

    private const val STIFFNESS = 400.0 // mass is 1
    private const val DAMPING_RATIO = 0.754
    private const val INITIAL_SCALE = .4f
    private const val CLOSE_SCALE = .8f
    private val sessions = WeakHashMap<View, Session>()

    /** Under-damped spring step response, t in seconds. */
    fun springProgress(t: Double): Double {
        val natural = sqrt(STIFFNESS)
        val damped = natural * sqrt(1.0 - DAMPING_RATIO * DAMPING_RATIO)
        val decay = exp(-DAMPING_RATIO * natural * t)
        val oscillation = cos(damped * t) +
            DAMPING_RATIO * natural / damped * sin(damped * t)
        return 1.0 - decay * oscillation
    }

    /** Kept as a small API for callers/tests; the session owns the real animator. */
    fun openAnimator(view: View, onEnd: () -> Unit = {}): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_DURATION_MS
            addUpdateListener {
                val p = springProgress(it.currentPlayTime / 1000.0).toFloat()
                view.alpha = p.coerceIn(0f, 1f)
                val scale = INITIAL_SCALE + (1f - INITIAL_SCALE) * p
                view.scaleX = scale.coerceIn(INITIAL_SCALE, 1.08f)
                view.scaleY = view.scaleX
            }
            addListener(object : AnimatorListenerAdapter() {
                private var fired = false
                override fun onAnimationEnd(animation: Animator) {
                    if (fired) return
                    fired = true
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd()
                }
            })
        }

    /** Source reference: popup.dart _openingOf(), _platterInControl = 0.41.
     * Interpolate the surface rectangle itself from the actual trigger rectangle.
     * This is not a 0.95 -> 1 decorative scale of an already visible menu.
     */
    private fun openingFrame(view: View, anchor: View?, above: Boolean, p: Float) {
        if (view.width <= 0 || view.height <= 0) return
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val a = IntArray(2)
        val v = IntArray(2)
        view.getLocationOnScreen(v)
        // Remove the previous frame's view translation from screen coordinates.
        val left = v[0] - view.translationX - view.pivotX * (1f - view.scaleX)
        val top = v[1] - view.translationY - view.pivotY * (1f - view.scaleY)
        anchor?.getLocationOnScreen(a)
        val ax = if (anchor != null) a[0] + anchor.width / 2f - left else w / 2f
        val ay = if (anchor != null) a[1] + anchor.height / 2f - top else if (above) h else 0f
        val extent = if (anchor != null) minOf(anchor.width, anchor.height).coerceAtLeast(1) * .41f
            else minOf(w, h) * .16f
        val start = (extent / w).coerceIn(.01f, .4f)
        val sx = (start + (1f - start) * p).coerceAtLeast(.001f)
        val sy = (sx * (w + (h - w) * p) / h).coerceAtLeast(.001f)
        val px = ax.coerceIn(0f, w)
        val py = ay.coerceIn(0f, h)
        view.pivotX = px
        view.pivotY = py
        view.scaleX = sx
        view.scaleY = sy
        view.translationX = ax + (w / 2f - ax) * p - (px + (w / 2f - px) * sx)
        view.translationY = ay + (h / 2f - ay) * p - (py + (h / 2f - py) * sy)
        view.alpha = p.coerceIn(0f, 1f)
    }

    /** Set the pivot from the actual anchor centre in the surface's local coordinates. */
    fun anchorPivot(surface: View, anchor: View, aboveAnchor: Boolean) {
        val anchorLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        surface.getLocationOnScreen(surfaceLocation)
        surface.pivotX = (anchorLocation[0] + anchor.width / 2f - surfaceLocation[0])
            .coerceIn(0f, surface.width.coerceAtLeast(1).toFloat())
        surface.pivotY = if (aboveAnchor) surface.height.toFloat() else 0f
    }

    /** Start the shared spring entrance; the first rendered frame is transparent. */
    fun animate(view: View, aboveAnchor: Boolean = false, anchor: View? = null) {
        cancel(view)
        view.alpha = 0f
        view.scaleX = INITIAL_SCALE
        view.scaleY = INITIAL_SCALE
        view.translationX = 0f
        view.translationY = 0f
        view.isEnabled = true
        val session = Session(view, anchor, aboveAnchor, Phase.OPEN, {})
        sessions[view] = session
        session.install()
    }

    /** Shrink/fade from the current frame and invoke [onEnd] only after natural completion. */
    fun exit(
        view: View,
        aboveAnchor: Boolean = false,
        anchor: View? = null,
        onEnd: () -> Unit,
    ) {
        val old = sessions.remove(view)
        old?.cancelSilently()
        if (!view.isAttachedToWindow) {
            onEnd()
            return
        }
        val session = Session(view, anchor, aboveAnchor, Phase.CLOSE, onEnd)
        sessions[view] = session
        session.install()
    }

    fun dismissWindow(surface: View, aboveAnchor: Boolean = false, after: () -> Unit) =
        exit(surface, aboveAnchor, null, after)

    /** Explicit lifecycle cancellation, useful to popup owners on teardown. */
    fun cancel(view: View) {
        sessions.remove(view)?.cancelSilently()
    }

    private enum class Phase { OPEN, CLOSE }

    private class Session(
        private val view: View,
        private val anchor: View?,
        private val aboveAnchor: Boolean,
        private val phase: Phase,
        private val completion: () -> Unit,
    ) {
        private var animator: ValueAnimator? = null
        private var ended = false
        private var installed = false
        private val attachListener = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = maybeStart()
            override fun onViewDetachedFromWindow(v: View) = cancelSilently()
        }
        private val anchorListener = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = maybeStart()
            override fun onViewDetachedFromWindow(v: View) = cancelSilently()
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> maybeStart() }

        fun install() {
            if (ended) return
            installed = true
            view.addOnAttachStateChangeListener(attachListener)
            view.addOnLayoutChangeListener(layoutListener)
            anchor?.addOnAttachStateChangeListener(anchorListener)
            maybeStart()
            // A PopupWindow may dispatch attach before its first layout pass.
            view.post { maybeStart() }
        }

        private fun maybeStart() {
            if (ended || animator != null || !view.isAttachedToWindow ||
                anchor?.isAttachedToWindow == false || view.width <= 0 || view.height <= 0) return
            if (anchor != null) anchorPivot(view, anchor, aboveAnchor)
            else {
                view.pivotX = view.width / 2f
                view.pivotY = if (aboveAnchor) view.height.toFloat() else 0f
            }
            if (phase == Phase.OPEN) startOpen() else startClose()
        }

        private fun startOpen() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OPEN_DURATION_MS
                addUpdateListener {
                    val p = springProgress(it.currentPlayTime / 1000.0).toFloat()
                    openingFrame(view, anchor, aboveAnchor, p)
                }
                addListener(endListener {
                    view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
                    view.translationX = 0f; view.translationY = 0f
                })
                start()
            }
        }

        private fun startClose() {
            val startAlpha = view.alpha.coerceIn(0f, 1f)
            val startScaleX = view.scaleX.coerceAtLeast(.001f)
            val startScaleY = view.scaleY.coerceAtLeast(.001f)
            // Closing an opening surface must never grow it back to .8.
            val targetScaleX = minOf(startScaleX, CLOSE_SCALE)
            val targetScaleY = minOf(startScaleY, CLOSE_SCALE)
            view.isEnabled = false
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CLOSE_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    view.alpha = startAlpha * (1f - f)
                    view.scaleX = startScaleX + (targetScaleX - startScaleX) * f
                    view.scaleY = startScaleY + (targetScaleY - startScaleY) * f
                }
                addListener(endListener {
                    view.alpha = 0f
                    view.scaleX = targetScaleX
                    view.scaleY = targetScaleY
                    view.isEnabled = true
                })
                start()
            }
        }

        private fun endListener(finalize: () -> Unit) = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (ended) return
                ended = true
                finalize()
                cleanup()
                completion()
            }
        }

        fun cancelSilently() {
            if (ended) return
            ended = true
            animator?.let {
                it.removeAllListeners()
                it.removeAllUpdateListeners()
                it.cancel()
            }
            animator = null
            cleanup()
        }

        private fun cleanup() {
            if (installed) {
                view.removeOnAttachStateChangeListener(attachListener)
                view.removeOnLayoutChangeListener(layoutListener)
                anchor?.removeOnAttachStateChangeListener(anchorListener)
                installed = false
            }
            if (sessions[view] === this) sessions.remove(view)
        }
    }
}
