package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/** Fixed-rate edge auto-scroll; decimals retain the established 1..10 scale. */
internal object GroupDragScroll {
    const val DEFAULT_SPEED_FACTOR = 0.5f
    fun speedFactorOf(prefValue: Float): Float = prefValue.coerceIn(1f, 10f) / 10f
    fun speedFactorOf(prefValue: Int): Float = speedFactorOf(prefValue.toFloat())
    fun prefSpeedFactor(): Float = speedFactorOf(DataStore.dragScrollSpeed)
    fun pixels(maxScrollPerFrame: Int, outOfBounds: Int, speedFactor: Float = DEFAULT_SPEED_FACTOR): Int {
        if (outOfBounds == 0) return 0
        val speed = (maxScrollPerFrame * speedFactor).toInt().coerceAtLeast(1)
        return if (outOfBounds > 0) speed else -speed
    }
    fun pixels(recycler: RecyclerView, outOfBounds: Int): Int = pixels(
        recycler.resources.getDimensionPixelSize(R.dimen.item_touch_helper_max_drag_scroll_per_frame),
        outOfBounds, prefSpeedFactor(),
    )
}
