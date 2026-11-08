package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R

/** During a click crossfade the coordinator, not Mediator, owns horizontal centering. */
class GroupPillTabLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = R.attr.tabStyle,
) : TabLayout(context, attrs, defStyleAttr) {
    var manualCentering = false

    // Separate from click centering: a tab rebuild must also reject animator/property
    // writes and HorizontalScrollView's computeScroll (which bypasses scrollTo).
    private var lockedScrollX: Int? = null
    internal val isViewportLocked get() = lockedScrollX != null

    internal fun lockViewport(scrollX: Int, position: Int) {
        lockedScrollX = scrollX
        stopScrollAnimations(position)
        restoreViewport()
    }

    internal fun restoreViewport() {
        lockedScrollX?.let { super.scrollTo(it, 0) }
    }

    internal fun unlockViewport() {
        if (lockedScrollX == null) return
        // Cancel again before releasing: callbacks during layout may have tried to
        // start a smooth scroll even though its frames were held by computeScroll.
        stopScrollAnimations(selectedTabPosition)
        restoreViewport()
        lockedScrollX = null
    }

    private fun stopScrollAnimations(position: Int) {
        if (position in 0 until tabCount) {
            // Reach Material directly even when a click owns manualCentering. Our
            // scrollTo override holds the snapshot while this cancels its animator.
            super.setScrollPosition(position, 0f, false, false)
        }
        // HorizontalScrollView's smoothScrollBy is final and its scroller private.
        // Two consecutive zero-distance calls enter its public abortAnimation path
        // (the second is inside ANIMATED_SCROLL_GAP), without reflection or a fling
        // that could transfer focus. The viewport lock also guards the first call.
        super.smoothScrollBy(0, 0)
        super.smoothScrollBy(0, 0)
    }

    override fun scrollTo(x: Int, y: Int) {
        if (lockedScrollX == null) super.scrollTo(x, y)
    }

    override fun computeScroll() {
        if (lockedScrollX == null) super.computeScroll()
    }

    override fun setScrollPosition(position: Int, offset: Float, updateSelectedText: Boolean) {
        if (!manualCentering && !isViewportLocked) super.setScrollPosition(position, offset, updateSelectedText)
    }
    override fun setScrollPosition(position: Int, offset: Float, updateSelectedText: Boolean,
                                   updateIndicator: Boolean) {
        if (!manualCentering && !isViewportLocked) super.setScrollPosition(position, offset, updateSelectedText, updateIndicator)
    }
    override fun selectTab(tab: Tab?, updateIndicator: Boolean) {
        // Preserve selected Tab metadata/callbacks, but do not launch a competing scroll animator.
        super.selectTab(tab, updateIndicator && !manualCentering && !isViewportLocked)
    }
}
