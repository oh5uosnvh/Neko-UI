package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R

/** Own the *visible* exit, then remove the window before delivering a choice.
 * PopupWindow.OnDismissListener alone is too early when platform exit transitions are enabled.
 */
internal class ChoiceMenuPopup(
    private val anchor: View,
    adapter: ThemedChoiceAdapter,
    selected: Int,
    private var onClosed: ((Int?) -> Unit)?,
) : PopupWindow(anchor.context), View.OnAttachStateChangeListener {
    private val surface = FrameLayout(anchor.context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_spinner_dropdown)
        layoutDirection = anchor.layoutDirection
    }
    val listView = ListView(anchor.context).apply {
        this.adapter = adapter
        layoutDirection = anchor.layoutDirection
        ThemedChoiceStyle.clipMenu(this)
    }
    private var pending: Int? = null
    private var exiting = false
    private var finished = false
    private var animator: ValueAnimator? = null

    init {
        surface.addView(listView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentView = surface
        isFocusable = true
        isOutsideTouchable = true
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = ThemedChoiceStyle.dp(anchor.context, 8).toFloat()
        // No second WindowManager/Transition exit after our animator. dismiss() is now synchronous.
        animationStyle = 0
        enterTransition = null
        exitTransition = null
        setOnDismissListener {
            if (!finished) {
                finished = true
                stopAnimation()
                anchor.removeOnAttachStateChangeListener(this)
                listView.onItemClickListener = null
                listView.adapter = null
                val callback = onClosed
                onClosed = null
                val choice = pending
                pending = null
                callback?.invoke(choice)
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (!exiting && !finished && position in 0 until adapter.count) {
                pending = position
                dismiss()
            }
        }

        val visible = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val maximum = minOf(ThemedChoiceStyle.maximumPopupWidth(anchor.context),
            (visible.width() - ThemedChoiceStyle.dp(anchor.context, 16)).takeIf { it > 0 }
                ?: ThemedChoiceStyle.maximumPopupWidth(anchor.context))
        val horizontalPadding = surface.paddingLeft + surface.paddingRight
        val contentMaximum = (maximum - horizontalPadding).coerceAtLeast(1)
        val rows = (0 until adapter.count).map { adapter.getView(it, null, listView) }
        val contentWidth = rows.maxOfOrNull {
            it.measure(View.MeasureSpec.makeMeasureSpec(contentMaximum, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.measuredWidth
        } ?: 0
        width = (contentWidth.coerceAtLeast(ThemedChoiceStyle.dp(anchor.context, 144)) +
            horizontalPadding).coerceAtMost(maximum)
        val contentHeight = rows.sumOf {
            it.measure(View.MeasureSpec.makeMeasureSpec((width - horizontalPadding).coerceAtLeast(1),
                View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.measuredHeight
        }
        height = (contentHeight + surface.paddingTop + surface.paddingBottom)
            .coerceAtMost(getMaxAvailableHeight(anchor, 0)).coerceAtLeast(1)
        listView.setSelection(selected)
    }

    fun show() {
        if (!anchor.isAttachedToWindow || anchor.windowToken == null) {
            cancelImmediately()
            return
        }
        anchor.addOnAttachStateChangeListener(this)
        try {
            showAsDropDown(anchor, 0, 0, Gravity.START)
            PopupEntrance.animate(surface, isAboveAnchor)
        } catch (_: WindowManager.BadTokenException) {
            cancelImmediately()
        }
    }

    override fun dismiss() {
        if (finished || exiting) return
        if (!isShowing || !surface.isAttachedToWindow) {
            cancelImmediately()
            return
        }
        exiting = true
        listView.isEnabled = false
        surface.pivotX = surface.width / 2f
        surface.pivotY = if (isAboveAnchor) surface.height.toFloat() else 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Matches popup_exit.xml, but completion is an animation event, never a timed post.
            duration = 100L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                surface.alpha = 1f - fraction
                surface.scaleX = 1f - .05f * fraction
                surface.scaleY = surface.scaleX
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    removeWindow()
                }
            })
            start()
        }
    }

    /** Rebind/detach is cancellation, including a selection still in its exit animation. */
    fun cancelImmediately() {
        pending = null
        stopAnimation()
        if (isShowing) removeWindow() else if (!finished) {
            finished = true
            anchor.removeOnAttachStateChangeListener(this)
            listView.onItemClickListener = null
            listView.adapter = null
            val callback = onClosed
            onClosed = null
            callback?.invoke(null)
        }
    }

    private fun stopAnimation() {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    private fun removeWindow() = super.dismiss()

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = cancelImmediately()
}
