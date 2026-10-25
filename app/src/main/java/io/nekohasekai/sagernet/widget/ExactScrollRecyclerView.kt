package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView

/** Uses RecyclerView's measured scroll range for the thumb, preserving safe drag behavior. */
class ExactScrollRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FastScrollRecyclerView(context, attrs, defStyleAttr) {

    private val scrollbar: Any? by lazy {
        runCatching {
            FastScrollRecyclerView::class.java.getDeclaredField("mScrollbar").apply {
                isAccessible = true
            }.get(this)
        }.getOrNull()
    }

    override fun updateThumbPosition(
        state: FastScrollRecyclerView.ScrollPositionState,
        scrollY: Int,
    ) {
        val bar = scrollbar ?: return
        val scrollable = (computeVerticalScrollRange() - computeVerticalScrollExtent())
            .coerceAtLeast(0)
        if (scrollable == 0) {
            setThumbPosition(bar, -1, -1)
            return
        }
        val offset = computeVerticalScrollOffset().coerceIn(0, scrollable)
        val fraction = offset.toFloat() / scrollable.toFloat()
        val y = paddingTop + (fraction * getAvailableScrollBarHeight()).toInt()
        setThumbPosition(bar, width - getScrollBarWidth(), y)
    }

    private fun setThumbPosition(bar: Any, x: Int, y: Int) {
        runCatching {
            bar.javaClass.getMethod(
                "setThumbPosition",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(bar, x, y)
        }
    }
}
