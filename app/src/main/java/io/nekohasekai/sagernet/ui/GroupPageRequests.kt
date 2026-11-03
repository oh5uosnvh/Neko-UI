package io.nekohasekai.sagernet.ui

import androidx.viewpager2.widget.ViewPager2

/** Latest stable-ID click wins. Never restart an in-flight ViewPager swipe/animation. */
internal class GroupPageRequests {
    private var target: Long? = null
    fun request(groupId: Long) { target = groupId }
    fun takeWhenIdle(idle: Boolean): Long? {
        if (!idle) return null
        val next = target
        target = null
        return next
    }
    /** Called once per frame/idle callback; real ViewPager2 smooth scrolling, no page rebuild. */
    fun applyWhenIdle(pager: ViewPager2, ids: List<Long>, restoring: Boolean = false): Boolean {
        val next = takeWhenIdle(!restoring && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) ?: return false
        val position = ids.indexOf(next)
        if (position < 0 || position == pager.currentItem) return false
        pager.setCurrentItem(position, true)
        return true
    }

    fun clear() { target = null }
}
