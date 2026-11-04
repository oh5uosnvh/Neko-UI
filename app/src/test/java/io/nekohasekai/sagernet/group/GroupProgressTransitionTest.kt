package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Contracts for direct click staging; gesture progress remains covered elsewhere. */
class GroupProgressTransitionTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private val source get() = File(app, "src/main/java/io/nekohasekai/sagernet/ui/GroupPageTransition.kt").readText()

    @Test fun clickStagesCentreThenDirectionalInjectionBeforeCards() {
        assertTrue(source.contains("TAB_CENTRE_MS = 180L"))
        assertTrue(source.contains("PILL_INJECTION_MS = 200L"))
        assertTrue(source.indexOf("centreTab(target)") < source.indexOf("startInjectionAndCards(target)"))
        assertTrue(source.contains("old?.updateProgress((1f - fraction) * start, toRight, false)"))
        assertTrue(source.contains("next?.updateProgress(fraction, !toRight, false)"))
    }

    @Test fun cardsCrossFadeWithOldSnapshotUntilIncomingHasStarted() {
        assertTrue(source.contains("pager.overlay.add"))
        assertTrue(source.contains("OLD_FADE_MS = 160f"))
        assertTrue(source.contains("INCOMING_DELAY_MS = 80f"))
        assertTrue(source.contains("INCOMING_FADE_MS = 160f"))
        assertTrue(source.contains("content?.alpha = 0f"))
        assertTrue(source.contains("content?.alpha = ((elapsed - INCOMING_DELAY_MS)"))
        assertTrue(source.contains("if (snapshot == null)"))
        assertFalse("the pager parent must not be hidden with its overlay", source.contains("pager.alpha = 0f"))
    }

    @Test fun swipeStillControlsOnlyNeighbourProgress() {
        assertTrue(source.contains("updateProgress(1f - offset, true)"))
        assertTrue(source.contains("updateProgress(offset, false)"))
    }
}
