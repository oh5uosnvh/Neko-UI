package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.ui.GroupPickerTagScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPickerTagScopeTest {
    @Test fun scopeKeepsRootOrderAndAllowsEmptyTag() {
        val roots = listOf(ProxyGroup(id = 7, name = "seven"), ProxyGroup(id = 3, name = "three"))
        assertEquals(listOf(7L), GroupPickerTagScope.groups(roots, setOf(7L)).map { it.id })
        assertEquals(listOf(7L, 3L), GroupPickerTagScope.groups(roots, setOf(3L, 7L)).map { it.id })
        assertTrue(GroupPickerTagScope.groups(roots, emptySet()).isEmpty())
    }

    @Test fun nodeImportNeverTargetsOutsideOrSubscriptionGroups() {
        val outside = ProxyGroup(id = 1, type = io.nekohasekai.sagernet.GroupType.BASIC)
        val subscription = ProxyGroup(id = 2, type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION)
        val manual = ProxyGroup(id = 3, type = io.nekohasekai.sagernet.GroupType.BASIC)
        val otherManual = ProxyGroup(id = 4, type = io.nekohasekai.sagernet.GroupType.BASIC)
        val roots = listOf(outside, subscription, manual, otherManual)
        assertEquals(3L, GroupPickerTagScope.importTarget(roots, setOf(2L, 3L, 4L), 1L)?.id)
        assertEquals(4L, GroupPickerTagScope.importTarget(roots, setOf(2L, 3L, 4L), 4L)?.id)
        assertEquals(null, GroupPickerTagScope.importTarget(roots, emptySet(), 1L))
        assertEquals(null, GroupPickerTagScope.importTarget(roots, setOf(2L), 2L))
    }

    @Test fun pendingDeletedManualGroupIsNeverAnImportTarget() {
        val pending = io.nekohasekai.sagernet.database.PendingGroupDeletes
        pending.mark(31L)
        try {
            assertEquals(null, GroupPickerTagScope.importTarget(
                listOf(ProxyGroup(id = 31, type = io.nekohasekai.sagernet.GroupType.BASIC)), setOf(31L), 31L))
        } finally { pending.unmark(listOf(31L)) }
    }

    @Test fun asyncImportContractsCaptureScopeAndPersistPendingFileTag() {
        val app = sequenceOf(java.io.File("."), java.io.File("app"))
            .first { java.io.File(it, "src/main/java").isDirectory }
        val source = java.io.File(app, "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(source.contains("topBar.dismissGroupPicker(immediate = true)"))
        assertTrue(source.contains("Show all groups"))
        assertTrue(source.contains("pendingFileTagId = currentScopeTag()"))
        assertTrue(source.contains("outState.putLong(\"pendingFileTagId\", pendingFileTagId ?: 0L)"))
        assertTrue(source.contains("savedInstanceState?.getLong(\"pendingFileTagId\", 0L)"))
        assertTrue(source.contains("val capturedTagId = pendingFileTagId"))
        assertTrue(source.contains("import(proxies, capturedTagId = capturedTagId)"))
        assertTrue(source.contains("importSubscriptionCaptured(e.link, capturedTagId)"))
        assertFalse(source.contains("scopeTagIdForImport"))
        val settings = java.io.File(app, "src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt").readText()
        assertFalse(settings.contains("?.takeIf { tagId -> TagGroupStore.get(tagId) != null }"))
    }

    @Test fun deletedOrInvalidTagCannotBeEntered() {
        val tags = listOf(TagGroup(4, "Work", 1))
        assertTrue(GroupPickerTagScope.valid(4, tags))
        assertFalse(GroupPickerTagScope.valid(9, tags))
        assertFalse(GroupPickerTagScope.valid(null, tags))
    }
}
