package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Contracts for the reworked click staging; gesture progress remains covered elsewhere. */
class GroupProgressTransitionTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private val source get() = File(app, "src/main/java/io/nekohasekai/sagernet/ui/GroupPageTransition.kt").readText()

    @Test fun adjacentClickReallyTranslatesInsteadOfFading() {
        assertTrue(source.contains("pager.setCurrentItem(target, true)"))
        assertTrue(source.contains("official behaviour, no fades"))
        // Adjacency must short-circuit before the staged click path starts.
        val adjacent = source.indexOf("abs(target - pager.currentItem) == 1")
        val staged = source.indexOf("clicking = true")
        assertTrue(adjacent in 0 until staged)
        // The adjacent block must not run a second scroll animator: the TabLayout
        // mediator already centres from the same per-frame pager progress, and the
        // two drivers shook the pill strip in the middle of the tab range.
        val block = source.substring(adjacent, staged)
        assertFalse(block.contains("centreTab"))
    }

    @Test fun rapidClicksRetargetTheLiveSettleInsteadOfQueueing() {
        // request() must begin immediately during a settle (never during a user drag,
        // which ViewPager2 forbids redirecting) instead of queueing until IDLE.
        assertTrue(source.contains("pager.scrollState != ViewPager2.SCROLL_STATE_DRAGGING) beginClick()"))
        assertFalse(source.contains("SCROLL_STATE_IDLE && !clicking) beginClick()"))
        // ViewPager2 redirects a settle when setCurrentItem is called mid-animation.
        assertTrue(source.contains("setCurrentItem is called mid-animation"))
    }

    @Test fun distantClickRunsEdgeInjectionAndPureTranslationTogether() {
        assertTrue(source.contains("centreTab(target) { startSelection(target) }"))
        assertTrue(source.contains("PILL_INJECTION_MS = 400L"))
        assertTrue(source.contains("CARD_SLIDE_MS = 400L"))
        // The card timeline starts from the same staged callback as the pill injection.
        assertTrue(source.indexOf("beginCardSlide(target, toRight)") > source.indexOf("injection = ValueAnimator"))
    }

    @Test fun cardsArePureTranslationWithNoCrossFadeAnywhere() {
        // Zero fade: no alpha timeline, no partial-width slide, no second interpolator.
        assertFalse(source.contains("OLD_FADE_MS"))
        assertFalse(source.contains("SLIDE_FRACTION"))
        assertFalse(source.contains("DecelerateInterpolator"))
        assertFalse(source.contains("content?.alpha = 0f"))
        assertFalse(source.contains("content?.alpha = progress"))
        assertFalse(source.contains("outgoing?.alpha ="))
        // Full-width rigid strip: snapshot exits toward the target side, new page enters
        // from the opposite edge, sharing one seam coordinate every frame.
        assertTrue(source.contains("(if (toRight) -1f else 1f) * pager.width"))
        assertTrue(source.contains("outgoing?.setBounds(drift, 0, width + drift, height)"))
        assertTrue(source.contains("pager.overlay.add"))
        assertTrue(source.contains("if (snapshot == null)"))
        assertFalse("the pager parent must never be hidden wholesale", source.contains("pager.alpha = 0f"))
        assertTrue(source.contains("resetCardLayer()"))
    }

    @Test fun swipeStillControlsOnlyNeighbourProgress() {
        assertTrue(source.contains("updateProgress(1f - offset, true)"))
        assertTrue(source.contains("updateProgress(offset, false)"))
    }
}
