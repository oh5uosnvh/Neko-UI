package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.GroupProfileExport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory fixtures only: no real clipboard, document, or user database access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupProfileExportTest {
    private fun profile(id: Long, groupId: Long, name: String) = ProxyEntity(
        id = id, groupId = groupId, type = ProxyEntity.TYPE_SOCKS,
        socksBean = SOCKSBean().apply {
            initializeDefaultValues()
            serverAddress = "node$id.example"
            serverPort = 1080
            this.name = name
        },
    )

    @Test fun readsOnlyTheRequestedGroupAndPreservesLegacyOrderAndFormat() {
        val requested = mutableListOf<Long>()
        val profiles = listOf(profile(8, 41, "first"), profile(3, 41, "second"))
        val groups = mapOf(41L to profiles, 52L to listOf(profile(9, 52, "other")))
        val links = GroupProfileExport.readLinks(41L) { id ->
            requested += id
            groups[id]
        }
        assertEquals(listOf(41L), requested)
        assertEquals(profiles.joinToString("\n") { it.toStdLink(compact = true) }, links)
        assertEquals(2, links.lines().size)
        assertFalse(links.endsWith("\n"))
        assertFalse(links.contains("node9.example"))
    }

    @Test fun validEmptyGroupRetainsTheOriginalEmptyExportFormat() {
        assertEquals("", GroupProfileExport.readLinks(41L) { emptyList() })
    }

    @Test fun missingGroupFailsInsteadOfExportingAnEmptyOrCurrentGroup() {
        val failure = assertThrows(IllegalStateException::class.java) {
            GroupProfileExport.readLinks(41L) { null }
        }
        assertEquals("Export group no longer exists", failure.message)
    }

    @Test fun invalidTargetDoesNotReadAnyGroup() {
        for (id in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                GroupProfileExport.readLinks(id) { fail("Invalid ID must not access the DAO"); emptyList() }
            }
        }
    }

    @Test fun queryAndSerializationFailuresAreNotSilentlySuccessful() {
        assertThrows(IllegalStateException::class.java) {
            GroupProfileExport.readLinks(41L) { error("synthetic query error") }
        }
        assertThrows(IllegalStateException::class.java) {
            GroupProfileExport.readLinks(41L) { listOf(ProxyEntity(groupId = 41L)) }
        }
    }
}
