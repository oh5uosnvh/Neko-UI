package io.nekohasekai.sagernet.ui

import android.app.Application
import android.content.Intent
import android.view.MenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupShortcutRegressionTest {
    @Test fun menuHasExactTopLevelOrder() {
        val context = RuntimeEnvironment.getApplication()
        val menu = MenuBuilder(context)
        MenuInflater(context).inflate(R.menu.add_profile_menu, menu)
        val add = menu.findItem(R.id.action_add).subMenu!!
        assertEquals(5, add.size())
        assertEquals(R.id.action_scan_qr_code, add.getItem(0).itemId)
        assertEquals(R.id.action_import_clipboard, add.getItem(1).itemId)
        assertEquals(R.id.action_import_file, add.getItem(2).itemId)
        assertEquals(R.id.action_new_group, add.getItem(3).itemId)
        assertTrue(add.getItem(4).hasSubMenu())
        assertFalse(add.getItem(3).hasSubMenu())
    }

    @Test fun deletingLastTabStaysOnLeftNeighbour() {
        val ids = listOf(1L, 2L, 3L, 4L, 10L, 11L, 12L, 13L, 15L, 16L)
        assertEquals(15L, GroupTabPolicy.afterRemoval(ids, 16L, 16L))
    }

    @Test fun deletingBackgroundTabKeepsCurrentSelection() {
        assertEquals(15L, GroupTabPolicy.afterRemoval(listOf(11, 12, 13, 15, 16).map(Int::toLong), 12L, 15L))
    }

    @Test fun deletingFirstTabUsesNextAndDeletingOnlyTabReturnsNull() {
        assertEquals(12L, GroupTabPolicy.afterRemoval(listOf(11L, 12L, 13L), 11L, 11L))
        assertNull(GroupTabPolicy.afterRemoval(listOf(11L), 11L, 11L))
    }

    @Test fun repeatedAndRapidRemovalAreIdempotent() {
        var ids = (1L..120L).toList()
        var selected = 120L
        for (id in 120L downTo 2L) {
            selected = GroupTabPolicy.afterRemoval(ids, id, selected)!!
            ids = ids.filter { it != id }
            assertEquals(id - 1, selected)
            assertEquals(selected, GroupTabPolicy.afterRemoval(ids, id, selected))
        }
        assertEquals(listOf(1L), ids)
    }
}
