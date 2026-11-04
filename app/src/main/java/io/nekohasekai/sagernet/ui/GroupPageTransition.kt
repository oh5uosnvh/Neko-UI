package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill

/**
 * Direct tab/picker selection is deliberately staged: centre the tab, inject its pill,
 * then cross-fade page cards. A bitmap of the old page keeps the content covered until
 * the new page has begun fading in, so no blank frame is ever exposed.
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
    private fun cancelFade() {
        fade?.removeAllListeners(); fade?.removeAllUpdateListeners(); fade?.cancel(); fade = null
        outgoing?.let { pager.overlay.remove(it) }; outgoing = null
        pager.alpha = 1f
        contentLayer()?.alpha = 1f
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
        clicking = true
        desired = null
        oldPill = pill(pager.currentItem)
        nextPill = pill(target)
        centreTab(target) { startInjectionAndCards(target) }
    }

    private fun startInjectionAndCards(target: Int) {
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
                override fun onAnimationEnd(animation: Animator) {
                    injection = null
                    if (clicking) crossFadeCards(target)
                }
            })
            start()
        }
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

    /** Old cards fade for 160 ms; incoming cards begin at 80 ms. */
    private fun crossFadeCards(target: Int) {
        val snapshot = captureOutgoing()
        // Overlay is a child of pager: hide only the internal RecyclerView, never the
        // pager itself, otherwise both old bitmap and new cards disappear together.
        if (snapshot == null) {
            pager.setCurrentItem(target, false)
            contentLayer()?.alpha = 1f
            clicking = false
            settlePills(target)
            onSettled(target)
            if (desired != null) beginClick()
            return
        }
        outgoing = snapshot.also { pager.overlay.add(it) }
        val content = contentLayer()
        content?.alpha = 0f
        pager.setCurrentItem(target, false)
        readyFrames = 0
        waitForIncoming(target, content)
    }

    private fun waitForIncoming(target: Int, content: View?) {
        if (!clicking || closed) return
        if (!isReady() && ++readyFrames < 180) {
            pager.postOnAnimation { waitForIncoming(target, content) }
            return
        }
        fade = ValueAnimator.ofFloat(0f, CROSS_FADE_TOTAL_MS.toFloat()).apply {
            duration = CROSS_FADE_TOTAL_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val elapsed = it.animatedValue as Float
                outgoing?.alpha = (255f * (1f - elapsed / OLD_FADE_MS).coerceIn(0f, 1f)).toInt()
                content?.alpha = ((elapsed - INCOMING_DELAY_MS) / INCOMING_FADE_MS).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fade = null
                    outgoing?.let { d -> pager.overlay.remove(d) }; outgoing = null
                    content?.alpha = 1f
                    clicking = false
                    settlePills(target)
                    onSettled(target)
                    if (desired != null) beginClick()
                }
            })
            start()
        }
    }

    private fun centreTab(position: Int, after: () -> Unit) {
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
        const val OLD_FADE_MS = 160f
        const val INCOMING_DELAY_MS = 80f
        const val INCOMING_FADE_MS = 160f
        const val CROSS_FADE_TOTAL_MS = 240L
    }
}
