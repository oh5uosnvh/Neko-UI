package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** View-local quick long press; normal DOWN/UP still go through View.onTouchEvent. */
@SuppressLint("ClickableViewAccessibility")
internal class QuickLongPress(
    private val view: View,
    private val action: (View) -> Boolean,
) : View.OnTouchListener, View.OnLongClickListener, View.OnAttachStateChangeListener {
    private val wasLongClickable = view.isLongClickable
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var bound = true
    private var touching = false
    private var cancelled = false
    private var triggered = false
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f

    private val longPress = Runnable {
        if (bound && touching && !cancelled && !triggered &&
            view.isAttachedToWindow && view.isShown && view.isEnabled
        ) {
            // Also supplies the platform haptic and accessibility long-click event.
            view.performLongClick()
        }
    }

    init {
        view.setOnTouchListener(this)
        view.setOnLongClickListener(this)
        view.addOnAttachStateChangeListener(this)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!bound) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                touching = true
                cancelled = event.pointerCount != 1 || !v.isEnabled
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                if (!cancelled) v.postDelayed(longPress, TIMEOUT_MILLIS)
                // Do not take ownership of DOWN: TabView still needs its native
                // pressed/click handling, and TabLayout must be able to scroll.
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (event.pointerCount != 1 || index < 0 || moved(event, index)) {
                    cancelTouch()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelTouch()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consume = triggered
                reset()
                // No cancelLongPress() on an ordinary UP: it would clear View's
                // PREPRESSED flag and lose short taps inside a scrolling parent.
                return consume
            }
        }
        return triggered
    }

    private fun moved(event: MotionEvent, index: Int): Boolean {
        fun beyondSlop(x: Float, y: Float): Boolean {
            val dx = x - downX
            val dy = y - downY
            return dx * dx + dy * dy > touchSlop * touchSlop
        }
        // A batched move may leave the slop region and return before delivery.
        for (sample in 0 until event.historySize) {
            if (beyondSlop(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample))) {
                return true
            }
        }
        return beyondSlop(event.getX(index), event.getY(index))
    }

    override fun onLongClick(v: View): Boolean {
        if (!bound || !v.isEnabled) return false
        if (touching) {
            // A platform timeout or accessibility event in the same touch stream
            // must never open another popup after the 200ms action/cancellation.
            if (cancelled || triggered) return true
            triggered = true
            v.isPressed = false
        }
        v.removeCallbacks(longPress)
        v.cancelLongPress()
        return action(v)
    }

    private fun cancelTouch() {
        cancelled = true
        view.removeCallbacks(longPress)
        view.cancelLongPress()
    }

    private fun reset() {
        view.removeCallbacks(longPress)
        touching = false
        cancelled = false
        triggered = false
        pointerId = -1
    }

    override fun onViewAttachedToWindow(v: View) = Unit

    override fun onViewDetachedFromWindow(v: View) {
        cancelTouch()
        reset()
        v.isPressed = false
    }

    /** Call before rebinding a pooled TabView and when the Fragment view dies. */
    fun unbind() {
        if (!bound) return
        bound = false
        cancelTouch()
        reset()
        view.isPressed = false
        view.removeOnAttachStateChangeListener(this)
        view.setOnTouchListener(null)
        view.setOnLongClickListener(null)
        view.isLongClickable = wasLongClickable
    }

    companion object {
        const val TIMEOUT_MILLIS = 200L
    }
}
