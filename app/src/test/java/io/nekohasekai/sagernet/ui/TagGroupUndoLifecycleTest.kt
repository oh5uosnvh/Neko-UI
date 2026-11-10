package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Independent contracts: the original Activity/Material snackbar, not a fixed timer. */
class TagGroupUndoLifecycleTest {
    private fun mainFile(path: String): File = sequenceOf(File("src/main/$path"), File("app/src/main/$path"))
        .first { it.exists() }
    private fun source(path: String) = mainFile(path).readText()
    private val fragment get() = source("java/io/nekohasekai/sagernet/ui/TagGroupFragment.kt")
    private fun section(start: String, end: String) = fragment.substringAfter(start).substringBefore(end)

    @Test fun deletionUsesOriginalActivitySnackbarAndDefaultDurationWithoutCountdown() {
        val undo = section("private fun showUndo(", "private fun updateUndoAction(")
        assertTrue(undo.contains("host.snackbar(getString(R.string.tag_deleted, undo.name))"))
        assertTrue(undo.contains("setAction(R.string.tag_undo)"))
        assertTrue(undo.contains("bar.show()"))
        for (legacy in listOf("undoTimer", "UNDO_DURATION_MS", "expires", "elapsedRealtime", "tag_undo_countdown")) {
            assertFalse("must not independently time undo: $legacy", fragment.contains(legacy))
        }
        assertFalse(undo.contains("setDuration("))
        assertFalse(undo.contains("duration ="))
        assertFalse(undo.contains("setText("))
        val host = source("java/io/nekohasekai/sagernet/ui/MainActivity.kt")
            .substringAfter("override fun snackbarInternal").substringBefore("override fun stateChanged")
        assertTrue("Neko owns duration", host.contains("Snackbar.LENGTH_LONG"))
        assertTrue("Neko owns decoration", source("java/io/nekohasekai/sagernet/ui/ThemedActivity.kt")
            .contains("OutlinedSnackbar.decorate(snackbarInternal(text))"))
    }

    @Test fun localizedDeleteMessagesHaveOnlyNameAndNoSecondsResource() {
        for (locale in listOf("values", "values-zh-rCN")) {
            val xml = mainFile("res/$locale/tag_groups.xml")
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
                .getElementsByTagName("string")
            var message: String? = null
            for (index in 0 until strings.length) {
                val node = strings.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertNotEquals("unused countdown must be removed", "tag_undo_countdown", name)
                if (name == "tag_deleted") message = node.textContent
            }
            assertNotNull(message)
            assertTrue(message!!.contains("%1\$s"))
            assertFalse(message.contains("%2\$"))
            assertFalse(message.contains("秒"))
            assertFalse(message.contains("·"))
        }
    }

    @Test fun onlyCurrentDismissalCanCloseTheUndoWindowAndActionConsumesBeforeIO() {
        val undo = section("private fun showUndo(", "private fun updateUndoAction(")
        assertTrue(undo.contains("if (pendingUndo !== undo || undoSnackbar !== bar) return@setAction"))
        val action = undo.substringAfter("bar.setAction(R.string.tag_undo)").substringBefore(".addCallback")
        assertTrue(action.indexOf("pendingUndo = null") < action.indexOf("undo.restore()"))
        assertTrue(action.indexOf("undoSnackbar = null") < action.indexOf("undo.restore()"))
        assertTrue(action.contains("withContext(Dispatchers.IO) { undo.restore() }"))
        assertTrue(action.contains("showMessage(R.string.tag_restored)"))
        assertFalse("an action must not restart its expired Material window", action.contains("showUndo("))
        val callback = undo.substringAfter("override fun onDismissed")
        assertTrue(callback.contains("if (undoSnackbar !== transientBottomBar) return"))
        assertTrue(callback.contains("event != DISMISS_EVENT_ACTION && pendingUndo === undo"))
        assertTrue(callback.contains("pendingUndo = null"))
    }

    @Test fun hideAndRecreationEndWindowWhileSameNameCreateDoesNotReplaceOrRestartIt() {
        val hidden = section("override fun onHiddenChanged(", "override fun onBackPressed(")
        val destroy = fragment.substringAfter("override fun onDestroyView(")
        assertTrue(hidden.contains("dismissUndo()"))
        assertTrue(destroy.contains("dismissUndo()"))
        assertFalse(hidden.contains("showUndo("))
        assertFalse(destroy.contains("showUndo("))
        assertFalse(section("override fun onViewCreated(", "override fun onResume(").contains("showUndo("))
        val savedName = section("internal fun onNameSaved(", "private fun showNameDialog(")
        assertTrue(savedName.contains("if (pendingUndo == null) showMessage("))
        assertFalse(savedName.contains("showUndo("))
        val delete = section("private fun delete(", "private fun showMessage(")
        assertTrue(delete.contains("TagGroupStore.delete(tag.id)"))
        assertTrue(delete.contains("PendingUndo(tag.name, record::restore)"))
        val dismissal = section("private fun dismissUndo(", "private fun runOperation(")
        assertTrue(dismissal.indexOf("pendingUndo = null") < dismissal.indexOf("bar?.dismiss()"))
        assertTrue(dismissal.indexOf("undoSnackbar = null") < dismissal.indexOf("bar?.dismiss()"))
    }

    @Test fun busyOperationsDisableExistingActionWithoutReplayingOrExtendingSnackbar() {
        val action = section("private fun updateUndoAction(", "private fun dismissUndo(")
        assertTrue(action.contains("com.google.android.material.R.id.snackbar_action"))
        assertTrue(action.contains("?.isEnabled = !busy"))
        assertFalse(action.contains("show()"))
        val operation = section("private fun runOperation(", "private fun installDrag(")
        assertTrue(operation.contains("busy = true\n        updateUndoAction()"))
        assertTrue(operation.contains("busy = false\n                    updateUndoAction()"))
        val setup = section("override fun onViewCreated(", "override fun onResume(")
        assertTrue(setup.contains("ToolbarIconPalette.apply(toolbar, requireContext())"))
    }

    @Test fun genericManagerRestoresDefaultWithoutRegressingCallbackSafeReentry() {
        val manager = source("java/io/nekohasekai/sagernet/widget/UndoSnackbarManager.kt")
        assertFalse(manager.contains("duration ="))
        assertFalse(manager.contains("setDuration("))
        val commit = manager.substringAfter("if (last === transientBottomBar").substringBefore("private var last")
        assertTrue(commit.indexOf("recycleBin.clear()") < commit.indexOf("callback.commit(actions)"))
        assertTrue(commit.indexOf("last = null") < commit.indexOf("callback.commit(actions)"))
        val action = manager.substringAfter("setAction(R.string.undo)").substringBefore("last = this")
        assertTrue(action.contains("recycleBin.reversed().toList()"))
        assertTrue(action.indexOf("recycleBin.clear()") < action.indexOf("callback.undo(actions)"))
        assertTrue(action.indexOf("last = null") < action.indexOf("callback.undo(actions)"))
    }
}
