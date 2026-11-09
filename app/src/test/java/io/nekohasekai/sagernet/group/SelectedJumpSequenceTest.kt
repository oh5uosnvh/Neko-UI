package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.SelectedJumpSequence
import org.junit.Assert.*
import org.junit.Test

class SelectedJumpSequenceTest {
    @Test fun firstSelectedSecondFirstThirdSelected() {
        val taps = SelectedJumpSequence()
        assertFalse(taps.returnToFirst(99, 9, 3))
        assertTrue(taps.returnToFirst(99, 9, 9))
        assertFalse(taps.returnToFirst(99, 9, 1))
    }
    @Test fun manualNavigationOrChangedSelectionResetsFirstClickIntent() {
        val taps = SelectedJumpSequence()
        assertFalse(taps.returnToFirst(99, 9, 9))
        assertFalse(taps.returnToFirst(99, 9, 12))
        assertFalse(taps.returnToFirst(100, 10, 9))
        assertTrue(taps.returnToFirst(100, 10, 10))
    }
    @Test fun titleInCurrentGroupAlternatesSelectedTopSelectedWithoutChangingGroup() {
        val title = SelectedJumpSequence()
        val groupId = 12L
        val selectedId = 880L
        assertFalse(title.returnToFirst(selectedId, groupId, groupId))
        assertTrue(title.returnToFirst(selectedId, groupId, groupId))
        assertFalse(title.returnToFirst(selectedId, groupId, groupId))
        assertTrue(title.returnToFirst(selectedId, groupId, groupId))
        // A different selected node starts at that node, never at the top.
        assertFalse(title.returnToFirst(881L, groupId, groupId))
        title.reset()
        assertFalse(title.returnToFirst(881L, groupId, groupId))
    }

    @Test fun noSelectionAlwaysFirstAndNeverArmsSecondClick() {
        val taps = SelectedJumpSequence()
        repeat(3) { assertTrue(taps.returnToFirst(0, 0, 5)) }
        assertFalse(taps.returnToFirst(99, 9, 1))
        taps.reset()
        assertFalse(taps.returnToFirst(99, 9, 9))
    }
}
