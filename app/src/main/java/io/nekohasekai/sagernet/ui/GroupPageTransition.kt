package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill

/** Click swaps only at alpha zero; hand swipes paint progress from actual pager distance. */
internal class GroupPageTransition(
    private val pager: ViewPager2,
    private val tabs: TabLayout,
    private val ids: () -> List<Long>,
    private val onSettled: (Int) -> Unit,
    private val isReady: () -> Boolean = { true },
) : ViewPager2.OnPageChangeCallback() {
    private var desired: Long? = null
    private var fade: ValueAnimator? = null
    private var centre: ObjectAnimator? = null
    private var oldPill: GroupTabPill? = null
    private var nextPill: GroupTabPill? = null
    private var lastSwipePosition = -1
    private var closed = false
    private var readyFrames = 0
    private val ready = object : Runnable {
        override fun run() {
            if (closed || !clicking) return
            if (isReady() || ++readyFrames >= 180) {
                animate(0f, 1f, 140L) {
                    pager.alpha = 1f
                    clicking = false
                    settlePills(pager.currentItem)
                    onSettled(pager.currentItem)
                    if (desired != null) beginClick()
                }
            } else pager.postOnAnimation(this)
        }
    }
    var clicking = false
        private set
    private fun pill(position: Int) = tabs.getTabAt(position)?.customView as? GroupTabPill
    private fun cancelFade() { fade?.removeAllListeners(); fade?.removeAllUpdateListeners(); fade?.cancel(); fade = null }
    private fun animate(from: Float, to: Float, duration: Long, finish: () -> Unit) {
        cancelFade()
        oldPill?.updateProgress(pager.alpha, false, true)
        fade = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                pager.alpha = fraction
                if (to == 0f) oldPill?.updateProgress(fraction, false, true)
                else nextPill?.updateProgress(fraction, false, true)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { fade = null; finish() }
            })
            start()
        }
    }
    fun request(groupId: Long) {
        desired = groupId
        if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && !clicking) beginClick()
    }
    private fun beginClick() {
        val groupId = desired ?: return
        val position = ids().indexOf(groupId)
        if (position < 0) { desired = null; return }
        centreTab(position)
        if (position == pager.currentItem) { desired = null; settlePills(position); return }
        clicking = true
        oldPill = pill(pager.currentItem)
        animate(pager.alpha, 0f, 100L) {
            val target = ids().indexOf(desired ?: groupId)
            desired = null
            if (target >= 0) {
                nextPill = pill(target)
                pager.setCurrentItem(target, false) // Invisible swap, never scroll through intermediate groups.
                centreTab(target)
                nextPill?.updateProgress(0f, false, true)
                readyFrames = 0
                pager.postOnAnimation(ready)
            } else { clicking = false; pager.alpha = 1f; settlePills(pager.currentItem) }
        }
    }
    private fun centreTab(position: Int) {
        (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = true
        val tab = tabs.getTabAt(position)?.view ?: return
        if (tabs.width == 0) return
        val content = tabs.getChildAt(0)
        val max = (content.width - tabs.width).coerceAtLeast(0)
        val destination = (tab.left + tab.width / 2 - tabs.width / 2).coerceIn(0, max)
        centre?.cancel()
        centre = ObjectAnimator.ofInt(tabs, "scrollX", tabs.scrollX, destination).apply {
            duration = 220L
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }
    private fun settlePills(position: Int) {
        for (index in 0 until tabs.tabCount) pill(index)?.settleSelected(index == position)
    }
    override fun onPageScrolled(position: Int, offset: Float, pixels: Int) {
        if (clicking) return
        // Only the neighbouring pair changes per frame; no tab rebuilds or database access.
        if (lastSwipePosition != position) {
            if (lastSwipePosition >= 0) { pill(lastSwipePosition)?.updateProgress(0f, false); pill(lastSwipePosition + 1)?.updateProgress(0f, false) }
            lastSwipePosition = position
        }
        pill(position)?.updateProgress(1f - offset, true)
        pill(position + 1)?.updateProgress(offset, false)
    }
    override fun onPageSelected(position: Int) {
        if (!clicking && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) { settlePills(position); onSettled(position) }
    }
    override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager2.SCROLL_STATE_DRAGGING) (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
        if (state == ViewPager2.SCROLL_STATE_DRAGGING && clicking) {
            cancelFade(); clicking = false; desired = null; pager.alpha = 1f
        }
        if (state == ViewPager2.SCROLL_STATE_IDLE && !clicking) {
            settlePills(pager.currentItem)
            onSettled(pager.currentItem)
            if (desired != null) beginClick()
        }
    }
    fun close() {
        closed = true
        pager.removeCallbacks(ready)
        desired = null; clicking = false
        cancelFade(); centre?.cancel(); centre = null
        pager.alpha = 1f
        (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
        oldPill = null; nextPill = null
    }
}
