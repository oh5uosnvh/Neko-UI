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
    }

    @Test fun distantClickRunsInjectionAndCardSlideTogether() {
        assertTrue(source.contains("centreTab(target) { startSelection(target) }"))
        assertTrue(source.contains("PILL_INJECTION_MS = 200L"))
        assertTrue(source.contains("CARD_SLIDE_MS = 200L"))
        assertTrue(source.contains("OLD_FADE_MS = 120f"))
        assertTrue(source.contains("SLIDE_FRACTION = 0.35f"))
        // Both timelines start from the same staged callback.
        assertTrue(source.indexOf("beginCardSlide(target, toRight)") > source.indexOf("injection = ValueAnimator"))
    }

    @Test fun cardsNeverGoBlankAndSlideDirectionFollowsTargetSide() {
        assertTrue(source.contains("pager.overlay.add"))
        assertTrue(source.contains("(if (toRight) -1f else 1f) * pager.width * SLIDE_FRACTION"))
        assertTrue(source.contains("if (snapshot == null)"))
        assertFalse("the pager parent must never be hidden wholesale", source.contains("pager.alpha = 0f"))
        assertTrue(source.contains("resetCardLayer()"))
    }

    @Test fun swipeStillControlsOnlyNeighbourProgress() {
        assertTrue(source.contains("updateProgress(1f - offset, true)"))
        assertTrue(source.contains("updateProgress(offset, false)"))
    }
}
