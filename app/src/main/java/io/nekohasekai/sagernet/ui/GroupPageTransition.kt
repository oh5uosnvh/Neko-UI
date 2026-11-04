package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill
import kotlin.math.abs

/**
 * Adjacent clicks use the official real page scroll — cards genuinely translate one page
 * and the pills follow the live pager distance. Distant clicks run two timelines at the
 * same time: the target pill centres, then injects its fill over 200 ms while the old
 * cards fade out and the new cards slide in from the trailing side, both finishing
 * together. A bitmap of the old page in pager.overlay keeps the content covered, so no
 * blank frame is ever exposed.
 */
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
    private var outgoing: BitmapDrawable? = null
    private var lastSwipePosition = -1
    private var readyFrames = 0
    private var closed = false
    var clicking = false
        private set

    private fun pill(position: Int) = tabs.getTabAt(position)?.customView as? GroupTabPill
    private fun contentLayer(): View? = pager.getChildAt(0)

    private fun resetCardLayer() {
        contentLayer()?.alpha = 1f
        contentLayer()?.translationX = 0f
        pager.alpha = 1f
    }

    private fun cancelFade() {
        fade?.removeAllListeners(); fade?.removeAllUpdateListeners(); fade?.cancel(); fade = null
        outgoing?.let { pager.overlay.remove(it) }; outgoing = null
        resetCardLayer()
    }

    private fun cancelMotion() {
        centre?.cancel(); centre = null
        injection?.cancel(); injection = null
        cancelFade()
    }

    fun request(groupId: Long) {
        desired = groupId
        if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && !clicking) beginClick()
    }

    private fun beginClick() {
        val groupId = desired ?: return
        val target = ids().indexOf(groupId)
        if (target < 0) { desired = null; return }
        if (target == pager.currentItem) { desired = null; settlePills(target); return }
        if (abs(target - pager.currentItem) == 1) {
            // Adjacent: official behaviour, no fades — the real settling scroll carries
            // the cards while the pills track the live pager distance.
            desired = null
            lastSwipePosition = -1
            (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
            pager.setCurrentItem(target, true)
            centreTab(target)
            return
        }
        clicking = true
        desired = null
        oldPill = pill(pager.currentItem)
        nextPill = pill(target)
        readyFrames = 0
        centreTab(target) { startSelection(target) }
    }

    /** Pill injection and the card slide start together and end together. */
    private fun startSelection(target: Int) {
        if (closed || !clicking) return
        val old = oldPill
        val next = nextPill
        val toRight = target > pager.currentItem
        val start = old?.selectionProgress?.takeIf { it > 0f } ?: 1f
        old?.updateProgress(start, toRight, false)
        next?.updateProgress(0f, !toRight, false)
        injection?.cancel()
        injection = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PILL_INJECTION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                old?.updateProgress((1f - fraction) * start, toRight, false)
                next?.updateProgress(fraction, !toRight, false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { injection = null }
            })
            start()
        }
        beginCardSlide(target, toRight)
    }

    private fun beginCardSlide(target: Int, toRight: Boolean) {
        val snapshot = captureOutgoing()
        if (snapshot == null) {
            // No bitmap (zero-size pager): hard-cut instead of risking a blank frame.
            pager.setCurrentItem(target, false)
            resetCardLayer()
            finishSelection(target)
            return
        }
        outgoing = snapshot.also { pager.overlay.add(it) }
        val content = contentLayer()
        content?.alpha = 0f
        // 1 → 3: the new cards enter from the left and travel right; reverse otherwise.
        val slideFrom = (if (toRight) -1f else 1f) * pager.width * SLIDE_FRACTION
        content?.translationX = slideFrom
        pager.setCurrentItem(target, false)
        readyFrames = 0
        waitForIncoming(target, content, slideFrom)
    }

    private fun waitForIncoming(target: Int, content: View?, slideFrom: Float) {
        if (!clicking || closed) return
        if (!isReady() && ++readyFrames < 180) {
            pager.postOnAnimation { waitForIncoming(target, content, slideFrom) }
            return
        }
        val motion = DecelerateInterpolator()
        fade = ValueAnimator.ofFloat(0f, CARD_SLIDE_MS.toFloat()).apply {
            duration = CARD_SLIDE_MS
            addUpdateListener {
                val elapsed = it.animatedValue as Float
                outgoing?.alpha = (255f * (1f - (elapsed / OLD_FADE_MS).coerceIn(0f, 1f))).toInt()
                val progress = (elapsed / CARD_SLIDE_MS).coerceIn(0f, 1f)
                content?.alpha = progress
                content?.translationX = slideFrom * (1f - motion.getInterpolation(progress))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fade = null
                    outgoing?.let { d -> pager.overlay.remove(d) }; outgoing = null
                    resetCardLayer()
                    finishSelection(target)
                }
            })
            start()
        }
    }

    private fun finishSelection(target: Int) {
        clicking = false
        settlePills(target)
        onSettled(target)
        if (desired != null) beginClick()
    }

    private fun captureOutgoing(): BitmapDrawable? {
        if (pager.width <= 0 || pager.height <= 0) return null
        val bitmap = Bitmap.createBitmap(pager.width, pager.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { pager.draw(it) }
        return BitmapDrawable(pager.resources, bitmap).apply {
            setBounds(0, 0, pager.width, pager.height)
            alpha = 255
        }
    }

    private fun centreTab(position: Int, after: () -> Unit = {}) {
        (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = true
        val tab = tabs.getTabAt(position)?.view
        if (tab == null || tabs.width == 0) { after(); return }
        val content = tabs.getChildAt(0)
        val max = (content.width - tabs.width).coerceAtLeast(0)
        val destination = (tab.left + tab.width / 2 - tabs.width / 2).coerceIn(0, max)
        centre?.cancel()
        centre = ObjectAnimator.ofInt(tabs, "scrollX", tabs.scrollX, destination).apply {
            duration = TAB_CENTRE_MS
            interpolator = FastOutSlowInInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { centre = null; after() }
            })
            start()
        }
    }

    private fun settlePills(position: Int) {
        injection?.cancel(); injection = null
        for (index in 0 until tabs.tabCount) pill(index)?.settleSelected(index == position)
    }

    override fun onPageScrolled(position: Int, offset: Float, pixels: Int) {
        if (clicking) return
        if (lastSwipePosition != position) {
            if (lastSwipePosition >= 0) {
                pill(lastSwipePosition)?.updateProgress(0f, false)
                pill(lastSwipePosition + 1)?.updateProgress(0f, false)
            }
            lastSwipePosition = position
        }
        pill(position)?.updateProgress(1f - offset, true)
        pill(position + 1)?.updateProgress(offset, false)
    }

    override fun onPageSelected(position: Int) {
        if (!clicking && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
            settlePills(position); onSettled(position)
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
            (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
            if (clicking) {
                clicking = false
                desired = null
                cancelMotion()
                settlePills(pager.currentItem)
            }
        }
        if (state == ViewPager2.SCROLL_STATE_IDLE && !clicking) {
            settlePills(pager.currentItem); onSettled(pager.currentItem)
            if (desired != null) beginClick()
        }
    }

    fun close() {
        closed = true; desired = null; clicking = false; cancelMotion()
        (tabs as? io.nekohasekai.sagernet.widget.GroupPillTabLayout)?.manualCentering = false
        oldPill = null; nextPill = null
    }

    private companion object {
        const val TAB_CENTRE_MS = 180L
        const val PILL_INJECTION_MS = 200L
        const val OLD_FADE_MS = 120f
        const val CARD_SLIDE_MS = 200L
        const val SLIDE_FRACTION = 0.35f
    }
}
