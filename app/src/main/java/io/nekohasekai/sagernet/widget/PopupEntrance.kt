package io.nekohasekai.sagernet.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Shared popup entrance: the same short alpha/scale reveal used by choice menus. */
object PopupEntrance {
    fun animate(view: View, aboveAnchor: Boolean = false, duration: Long = 150L) {
        if (!view.isAttachedToWindow) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.pivotX = view.width / 2f
        view.pivotY = if (aboveAnchor) view.height.toFloat() else 0f
        view.alpha = 0f
        view.scaleX = .95f
        view.scaleY = .95f
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                view.alpha = f
                view.scaleX = .95f + .05f * f
                view.scaleY = view.scaleX
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
            start()
        }
    }
}
