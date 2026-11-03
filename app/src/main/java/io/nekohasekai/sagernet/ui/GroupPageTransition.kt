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
import kotlin.math.abs

/** Adjacent clicks really scroll one page; distant clicks hard-cut invisible, inject pills, fade in on ready. */
internal class GroupPageTransition(
    private val pager: ViewPager2,
    private val tabs: TabLayout,
    private val ids: () -> List<Long>,
    private val onSettled: (Int) -> Unit,
    private val isReady: () -> Boolean = { true },
) : ViewPager2.OnPageChangeCallback() {
    private var desired: Long? = null
    private var fade: ValueAnimator? = null
    private var injection: ValueAnimator? = null
    private var centre: ObjectAnimator? = null
    private var oldPill: GroupTabPill? = null
    private var nextPill: GroupTabPill? = null
    private var lastSwipePosition = -1
    private var closed = false
    private var readyFrames = 0
    var clicking = false
        private set
    private val ready = object : Runnable {
        override fun run() {
            if (closed || !clicking) return
            if (isReady() || ++readyFrames >= 180) {
                fadePager(0f, 1f, 140L) {
                    pager.alpha = 1f
                    clicking = false
                    settlePills(pager.currentItem)
                    onSettled(pager.currentItem)
                    if (desired != null) beginClick()
                }
            } else pager.postOnAnimation(this)
        }
    }
    private fun pill(position: Int) = tabs.getTabAt(position)?.customView as? GroupTabPill
    private fun cancelFade() { fade?.removeAllListeners(); fade?.removeAllUpdateListeners(); fade?.cancel(); fade = null }
    /** Drives only pager.alpha; pills are painted by the injection animator or by real scroll progress. */
    private fun fadePager(from: Float, to: Float, duration: Long, finish: () -> Unit) {
        cancelFade()
        fade = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                pager.alpha = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { fade = null; finish() }
            })
            start()
        }
    }
    /** Old pill contracts toward its trailing edge while the new pill grows from the leading edge, matching swipe geometry. */
    private fun startInjection(start: Float, toRight: Boolean) {
        injection?.cancel(); injection = null
        val old = oldPill ?: return
        val next = nextPill ?: return
        injection = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                old.updateProgress((1f - fraction) * start, toRight, false)
                next.updateProgress(fraction, !toRight, false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    injection = null
                    old.settleSelected(false)
                    next.settleSelected(true)
                }
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
        val target = ids().indexOf(groupId)
        if (target < 0) { desired = null; return }
        centreTab(target)
        if (target == pager.currentItem) { desired = null; settlePills(target); return }
        val current = pager.currentItem
        if (abs(target - current) == 1) {
            // Neighbour: real settling scroll carries the cards; pills follow the actual pager distance, never a fade.
            desired = null
            lastSwipePosition = -1
            pager.setCurrentItem(target, true)
            return
        }
        // Distant: same-frame hard cut, never fade out; inject pills while the page readies, then fade in.
        clicking = true
        desired = null
        oldPill = pill(current)
        nextPill = pill(target)
        val toRight = target > current
        val start = oldPill?.selectionProgress?.takeIf { it > 0f } ?: 1f
        oldPill?.updateProgress(start, toRight, false)
        nextPill?.updateProgress(0f, !toRight, false)
        pager.alpha = 0f
        pager.setCurrentItem(target, false) // Invisible swap, never scroll through intermediate groups.
        centreTab(target)
        startInjection(start, toRight)
        readyFrames = 0
        pager.postOnAnimation(ready)
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
        injection?.cancel(); injection = null
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
            injection?.cancel(); injection = null
            cancelFade(); clicking = false; desired = null; pager.alpha = 1f
            settlePills(pager.currentItem)
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
        injection?.cancel(); injection = null
        cancelFade(); centre?.cancel(); centre = null
        pager.alpha = 1f
        (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
        oldPill = null; nextPill = null
    }
}
