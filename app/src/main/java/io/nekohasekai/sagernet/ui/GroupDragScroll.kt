package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/**
 * 列表卡片拖动到上下边缘时的自动滚动。
 *
 * 语义（REQ-04）：整个拖动过程统一速度——列表边缘做**匀速**自动滚动，
 * 不随停留时间渐进加速；速度由偏好 dragScrollSpeed（范围 1..10，默认 5）
 * 驱动，实际系数 = V / 10.0f，V 越大越快。默认 5 → 0.5×，即分组页的手感。
 */
internal object GroupDragScroll {
    /** 偏好缺省档位对应的系数（默认 5 → 0.5×，分组页历史手感） */
    const val DEFAULT_SPEED_FACTOR = 0.5f

    /** 把偏好档位钳制到 1..10 后折算成系数：0 → 0.1，11 → 1.0 */
    fun speedFactorOf(prefValue: Int): Float = prefValue.coerceIn(1, 10) / 10.0f

    /** 读取当前偏好档位并折算系数（每次滚动现读，改设置即时生效） */
    fun prefSpeedFactor(): Float = speedFactorOf(DataStore.dragScrollSpeed)

    fun pixels(
        maxScrollPerFrame: Int,
        outOfBounds: Int,
        speedFactor: Float = DEFAULT_SPEED_FACTOR,
    ): Int {
        if (outOfBounds == 0) return 0
        val speed = (maxScrollPerFrame * speedFactor).toInt().coerceAtLeast(1)
        return if (outOfBounds > 0) speed else -speed
    }

    fun pixels(recycler: RecyclerView, outOfBounds: Int): Int = pixels(
        recycler.resources.getDimensionPixelSize(R.dimen.item_touch_helper_max_drag_scroll_per_frame),
        outOfBounds,
        prefSpeedFactor(),
    )
}
