package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R

/** Exactly the group page's edge auto-scroll speed: fixed 50%, never accelerates with dwell time. */
internal object GroupDragScroll {
    fun pixels(maxScrollPerFrame: Int, outOfBounds: Int): Int {
        if (outOfBounds == 0) return 0
        val speed = (maxScrollPerFrame * 0.5f).toInt().coerceAtLeast(1)
        return if (outOfBounds > 0) speed else -speed
    }
    fun pixels(recycler: RecyclerView, outOfBounds: Int): Int = pixels(
        recycler.resources.getDimensionPixelSize(R.dimen.item_touch_helper_max_drag_scroll_per_frame),
        outOfBounds,
    )
}
