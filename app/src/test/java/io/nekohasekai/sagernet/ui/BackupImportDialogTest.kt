package io.nekohasekai.sagernet.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupImportDialogTest {
    private fun inflater(): LayoutInflater = LayoutInflater.from(
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
    )

    @Test fun importOffersEveryOptionAndOnlyGroupsAndProfilesAreChecked() {
        // Both file and WebDAV use this exact factory, with no JSON-dependent visibility.
        val dialog = BackupImportDialog.inflate(inflater())
        val options = listOf(dialog.backupConfigurations, dialog.appendBackupConfigurations,
            dialog.backupRules, dialog.backupSettings)
        assertEquals(4, options.size)
        options.forEach { assertEquals(View.VISIBLE, it.visibility); assertTrue(it.isEnabled) }
        assertTrue(dialog.backupConfigurations.isChecked)
        assertFalse(dialog.appendBackupConfigurations.isChecked)
        assertFalse(dialog.backupRules.isChecked)
        assertFalse(dialog.backupSettings.isChecked)
        // Every requested option remains selectable, including optional sections absent in JSON.
        options.forEach { it.isChecked = !it.isChecked; assertEquals(View.VISIBLE, it.visibility) }
    }

    @Test fun importContainsOnlyOptionsWithoutExtraNotice() {
        val dialog = BackupImportDialog.inflate(inflater())
        val root = dialog.root as android.view.ViewGroup
        assertEquals(4, root.childCount)
        for (i in 0 until root.childCount) assertTrue(root.getChildAt(i) is android.widget.CheckBox)
        dialog.appendBackupConfigurations.isChecked = true
        dialog.backupRules.isChecked = true
        dialog.backupSettings.isChecked = true
        assertEquals(4, root.childCount)
    }

    @Test fun exportOffersAllSectionsWithOnlyGroupsAndProfilesChecked() {
        val backup = LayoutBackupBinding.inflate(inflater())
        listOf(backup.backupConfigurations, backup.backupRules, backup.backupSettings).forEach {
            assertEquals(View.VISIBLE, it.visibility)
            assertTrue(it.isEnabled)
        }
        assertTrue(backup.backupConfigurations.isChecked)
        assertFalse(backup.backupRules.isChecked)
        assertFalse(backup.backupSettings.isChecked)
    }
}
