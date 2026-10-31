package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import android.view.MotionEvent
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView

/** Uses measured scroll metrics and keeps the thumb anchored to the user's finger. */
class ExactScrollRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FastScrollRecyclerView(context, attrs, defStyleAttr) {

    private val scrollbarField = FastScrollRecyclerView::class.java.getDeclaredField("mScrollbar").apply {
        isAccessible = true
    }
    private val touchOffsetField by lazy { scrollbarClass().getDeclaredField("mTouchOffset").apply { isAccessible = true } }
    private val draggingField by lazy { scrollbarClass().getDeclaredField("mIsDragging").apply { isAccessible = true } }
    private val thumbPositionField by lazy { scrollbarClass().getDeclaredField("mThumbPosition").apply { isAccessible = true } }
    private val thumbMethod by lazy {
        scrollbarClass().getMethod(
            "setThumbPosition",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    }

    private var downY = 0
    private var grabOffset = 0
    private var trackingTouch = false

    private fun scrollbar(): Any = scrollbarField.get(this)

    private fun scrollbarClass() = scrollbarField.type

    private fun thumbPosition(): Point = thumbPositionField.get(scrollbar()).let { it as Point }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val y = event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = y
                val thumb = thumbPosition()
                val touchInset = (24 * resources.displayMetrics.density).toInt()
                val x = event.x.toInt()
                val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
                val thumbLeft = if (isRtl) 0 else thumb.x - touchInset
                val thumbRight = if (isRtl) getScrollBarWidth() + touchInset else thumb.x + getScrollBarWidth() + touchInset
                trackingTouch = thumb.x >= 0 && thumb.y >= 0 &&
                    x in thumbLeft..thumbRight &&
                    y >= thumb.y - touchInset &&
                    y <= thumb.y + getScrollBarThumbHeight() + touchInset
                if (trackingTouch) {
                    grabOffset = (y - thumb.y).coerceIn(0, getScrollBarThumbHeight())
                }
            }
            MotionEvent.ACTION_MOVE -> if (trackingTouch) {
                // On the first MOVE FastScroller adds (lastY - downY) before starting
                // the drag. Cancel that one duplicated delta; later moves preserve the
                // original grab offset because FastScroller no longer adds it while dragging.
                val bar = scrollbar()
                val offset = if (draggingField.getBoolean(bar)) {
                    grabOffset
                } else {
                    grabOffset - (y - downY)
                }
                touchOffsetField.setInt(bar, offset)
            }
        }

        val handled = super.dispatchTouchEvent(event)

        if (trackingTouch && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val bar = scrollbar()
            if (draggingField.getBoolean(bar)) {
                touchOffsetField.setInt(bar, grabOffset)
                // FastScroller recalculates the thumb from content position during the same
                // frame. Restore the grab point after that calculation so the thumb stays under
                // the finger instead of jumping away from it.
                val thumbY = (y - grabOffset).coerceIn(
                    paddingTop,
                    height - paddingBottom - getScrollBarThumbHeight(),
                )
                val thumbX = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else width - getScrollBarWidth()
                thumbMethod.invoke(bar, thumbX, thumbY)
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            trackingTouch = false
            grabOffset = 0
        }
        return handled
    }

    override fun updateThumbPosition(
        state: FastScrollRecyclerView.ScrollPositionState,
        scrollY: Int,
    ) {
        val bar = scrollbar()
        if (draggingField.getBoolean(bar) && trackingTouch) return

        val scrollable = (computeVerticalScrollRange() - computeVerticalScrollExtent())
            .coerceAtLeast(0)
        if (scrollable == 0) {
            thumbMethod.invoke(bar, -1, -1)
            return
        }
        val offset = computeVerticalScrollOffset().coerceIn(0, scrollable)
        val fraction = offset.toFloat() / scrollable.toFloat()
        val y = paddingTop + (fraction * getAvailableScrollBarHeight()).toInt()
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else width - getScrollBarWidth()
        thumbMethod.invoke(bar, x, y)
    }
}
