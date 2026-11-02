package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Bundle
import io.nekohasekai.sagernet.ui.PendingGroupExport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PendingGroupExportTest {
    @Test fun targetDoesNotChangeWhenAnotherTabOrMenuBecomesSelected() {
        val pending = PendingGroupExport()
        assertTrue(pending.begin(27L))
        assertFalse(pending.begin(99L)) // A second request cannot replace an open picker.
        assertEquals(27L, pending.pendingExportGroupId)
        assertEquals(27L, pending.consume())
        assertNull(pending.consume())
        assertTrue(pending.begin(99L))
        assertEquals(99L, pending.consume())
    }

    @Test fun targetSurvivesConfigurationAndProcessStateRestore() {
        val before = PendingGroupExport()
        assertTrue(before.begin(730L))
        val state = Bundle()
        before.saveState(state)
        val restored = PendingGroupExport()
        restored.restoreState(state)
        assertEquals(730L, restored.pendingExportGroupId)
        assertFalse(restored.begin(12L))
        assertEquals(730L, restored.consume())
    }

    @Test fun cancelClearsTargetAndStateSoLaterResultCannotUseIt() {
        val pending = PendingGroupExport()
        pending.begin(7L)
        val state = Bundle()
        pending.saveState(state)
        pending.consume() // Called on both null URI and launch failure.
        assertNull(pending.pendingExportGroupId)
        pending.saveState(state)
        val restored = PendingGroupExport()
        restored.restoreState(state)
        assertNull(restored.pendingExportGroupId)
        assertNull(restored.consume())
        assertTrue(restored.begin(8L))
    }

    @Test fun invalidIdsAndMissingStateCannotFallBackToASelectedGroup() {
        val pending = PendingGroupExport()
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) assertFalse(pending.begin(id))
        assertNull(pending.pendingExportGroupId)
        pending.restoreState(Bundle())
        assertNull(pending.consume())
        pending.begin(2L)
        pending.restoreState(null)
        assertNull(pending.consume())
    }
}
