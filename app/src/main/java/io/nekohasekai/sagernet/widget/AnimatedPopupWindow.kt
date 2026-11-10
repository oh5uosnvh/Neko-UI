package io.nekohasekai.sagernet.widget

import android.view.View
import android.widget.PopupWindow

/**
 * Legacy PopupWindow adapter whose every platform dismissal follows the shared
 * surface animation.  This is deliberately a window-level shim: outside touch
 * and BACK both arrive here, instead of bypassing PopupEntrance.
 */
open class AnimatedPopupWindow(
    content: View,
    width: Int,
    height: Int,
    focusable: Boolean,
    anchor: View? = null,
) : PopupWindow(content, width, height, focusable) {
    private var closing = false
    private var immediate = false
    private var afterAnimatedDismiss: (() -> Unit)? = null
    private var anchorView: View? = null
    private var scrimToken: Any? = null
    private val anchorAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = cancelImmediately()
    }

    var animationAnchor: View?
        get() = anchorView
        set(value) {
            anchorView?.removeOnAttachStateChangeListener(anchorAttach)
            anchorView = value
            value?.addOnAttachStateChangeListener(anchorAttach)
        }

    init {
        // PopupEntrance is the sole motion owner. Platform transitions would
        // otherwise run a second fade/scale after (or before) this one.
        animationStyle = 0
        enterTransition = null
        exitTransition = null
        animationAnchor = anchor
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // A teardown can detach the surface while an exit is pending.
                // Never leave a session or a window around in that case.
                PopupEntrance.cancel(v)
                if (isShowing && !immediate) cancelImmediately()
            }
        })
    }

    private fun superDismiss() {
        anchorView?.removeOnAttachStateChangeListener(anchorAttach)
        PopupScrimController.detach(scrimToken)
        scrimToken = null
        immediate = true
        super.dismiss()
        immediate = false
        afterAnimatedDismiss = null
    }

    private fun attachScrim(anchor: View) {
        if (scrimToken == null) scrimToken = PopupScrimController.attach(anchor)
    }

    override fun showAsDropDown(anchor: View, xoff: Int, yoff: Int) {
        attachScrim(anchor)
        super.showAsDropDown(anchor, xoff, yoff)
    }

    override fun showAsDropDown(anchor: View, xoff: Int, yoff: Int, gravity: Int) {
        attachScrim(anchor)
        super.showAsDropDown(anchor, xoff, yoff, gravity)
    }

    override fun showAtLocation(parent: View, gravity: Int, x: Int, y: Int) {
        attachScrim(parent)
        super.showAtLocation(parent, gravity, x, y)
    }

    /** Immediate lifecycle cancellation; no stale completion callback is run. */
    fun cancelImmediately() {
        if (immediate) return
        closing = true
        afterAnimatedDismiss = null
        PopupEntrance.cancel(contentView)
        superDismiss()
    }

    /** Close with the same shrink/fade, then run [after] exactly once. */
    fun dismissAnimated(after: () -> Unit) {
        if (closing) return
        afterAnimatedDismiss = after
        beginDismiss()
    }

    override fun dismiss() {
        if (immediate) {
            super.dismiss()
            return
        }
        // A platform/external dismissal cancels queued selection even if its
        // visible close has already begun. Do not commit stale navigation.
        afterAnimatedDismiss = null
        if (closing) return
        beginDismiss()
    }

    private fun beginDismiss() {
        if (!isShowing) {
            cancelImmediately()
            return
        }
        closing = true
        PopupEntrance.exit(contentView, isAboveAnchor, anchorView) {
            // PopupEntrance has already cancelled its session before this runs.
            val callback = afterAnimatedDismiss
            afterAnimatedDismiss = null
            superDismiss()
            callback?.invoke()
        }
    }
}
