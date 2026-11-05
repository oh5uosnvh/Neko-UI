package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import io.nekohasekai.sagernet.R

/** FragmentManager owns show/hide/removal; this owns only horizontal presentation. */
internal class PageSlideTransition(private val onSettled: () -> Unit = {}) {
    private val running = LinkedHashMap<Animator, View>()
    val isRunning: Boolean get() = running.isNotEmpty()

    fun create(view: View, enter: Boolean, nextAnim: Int): Animator? {
        val forward = when (nextAnim) {
            R.animator.page_slide_forward -> true
            R.animator.page_slide_backward -> false
            else -> return null // Startup, restored pages and warmup are deliberately motionless.
        }
        val width = ((view.parent as? View)?.width ?: 0).takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        val direction = if (forward) 1f else -1f
        val from = if (enter) direction * width else 0f
        val to = if (enter) 0f else -direction * width
        view.alpha = 1f
        view.translationX = from
        return ValueAnimator.ofFloat(from, to).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            running[this] = view
            addUpdateListener { view.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = settle(animation)
            })
        }
    }

    private fun settle(animation: Animator) {
        val view = running.remove(animation) ?: return
        (animation as? ValueAnimator)?.removeAllUpdateListeners()
        view.translationX = 0f
        view.alpha = 1f
        view.postInvalidateOnAnimation()
        if (running.isEmpty()) onSettled()
    }

    fun cancel() {
        running.keys.toList().forEach {
            it.cancel()
            // cancel() on an animator created but not started does not dispatch onAnimationEnd.
            settle(it)
        }
    }

    companion object { const val DURATION_MS = 260L }
}
