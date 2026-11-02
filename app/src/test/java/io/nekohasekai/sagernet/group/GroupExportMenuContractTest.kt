package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cheap source contracts complement the real MotionEvent / saved-state tests. */
class GroupExportMenuContractTest {
    private val app = sequenceOf(File("."), File("app")).firstOrNull {
        File(it, "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").isFile
    } ?: error("Run from the app module or repository root")

    private fun source(name: String) =
        File(app, "src/main/java/io/nekohasekai/sagernet/$name.kt").readText()

    @Test fun tabMenuHasExactOrderAndTwoDashedSeparators() {
        val menu = source("ui/ConfigurationFragment")
            .substringAfter("private fun showTabGroupMenu(")
            .substringBefore("fun deleteGroupWithUndo(")
        val rows = Regex("menuItem\\(getString\\(R\\.string\\.(\\w+)\\)\\)|addRow \\{ dashedDivider\\(\\) \\}")
            .findAll(menu).map { it.groupValues[1].ifEmpty { "divider" } }.toList()
        assertEquals(listOf("edit", "jump_to_group", "divider", "action_export_clipboard",
            "action_export_file", "divider", "clear_profiles", "delete"), rows)
        assertTrue(menu.contains("groupProfileExport.toClipboard(group.id)"))
        assertTrue(menu.contains("groupProfileExport.toFile(group.id, group.displayName())"))
    }

    @Test fun bothFragmentsCreateTheSameExporterAndSaveItsState() {
        for (name in listOf("ConfigurationFragment", "GroupFragment")) {
            val fragment = source("ui/$name")
            assertTrue(fragment.contains("private val groupProfileExport = GroupProfileExport(this)"))
            assertTrue(fragment.contains("groupProfileExport.restoreState(savedInstanceState)"))
            assertTrue(fragment.contains("groupProfileExport.saveState(outState)"))
        }
        val group = source("ui/GroupFragment")
        assertFalse(group.contains("selectedGroup"))
        assertTrue(group.contains("val menuGroup = proxyGroup"))
        assertTrue(group.contains("groupProfileExport.toClipboard(menuGroup.id)"))
        assertTrue(group.contains("groupProfileExport.toFile(menuGroup.id, menuGroup.displayName())"))
    }

    @Test fun exporterUsesOnlyGroupDaoAndLegacyNodeLinksWithSafeErrors() {
        val exporter = source("ui/GroupProfileExport")
        assertTrue(exporter.contains("getById(groupId)"))
        assertTrue(exporter.contains("getByGroup(groupId)"))
        assertTrue(exporter.contains("profiles.joinToString(\"\\n\") { it.toStdLink(compact = true) }"))
        assertTrue(exporter.contains("ActivityResultContracts.CreateDocument(\"text/plain\")"))
        assertTrue(exporter.indexOf("pending.consume()") < exporter.indexOf("if (uri != null"))
        assertTrue(exporter.contains("Snackbar.LENGTH_SHORT"))
        for (forbidden in listOf("DataStore", "selectedGroup", "readableMessage", "Logs.",
            "printStackTrace", ".toUniversalLink()", ".exportConfig()")) {
            assertFalse("Unexpected export behavior: $forbidden", exporter.contains(forbidden))
        }
    }

    @Test fun tabBindingUsesStableIdentityAndHasViewLifecycleCleanup() {
        val fragment = source("ui/ConfigurationFragment")
        val binding = fragment.substringAfter("groupTabsMediator = TabLayoutMediator(")
            .substringBefore("toolbar.setOnClickListener")
        assertTrue(binding.contains("val groupId = adapter.groupList.getOrNull(position)?.id"))
        assertTrue(binding.contains("adapter.groupList.firstOrNull { it.id == groupId }"))
        assertTrue(binding.contains("tab.position >= 0"))
        assertTrue(binding.contains("QuickLongPress(tab.view)"))
        assertTrue(binding.contains(".unbind()"))
        assertTrue(fragment.contains("groupTabsMediator?.detach()"))
        assertTrue(fragment.contains("tabLongPressBindings.clear()"))
        assertFalse(source("widget/QuickLongPress").contains("Settings.Secure"))
    }
}
