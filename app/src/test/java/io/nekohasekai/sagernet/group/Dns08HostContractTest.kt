package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Dns08HostContractTest {
    @Test fun connectionErrorsUseOfficialSnackbarNeverTheAddedLargeDialog() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        val main=File(app,"src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
        assertFalse(main.contains("showControlErrorDialog"))
        val failure=main.substringAfter("private fun showControlErrorSnackbar").substringBefore("private fun refreshConfigurationProfileState")
        assertTrue(failure.contains("snackbar(getString(R.string.connection_test_error, detail)).show()"))
        assertFalse(failure.contains("MaterialAlertDialogBuilder"))
        assertFalse(main.substringAfter("private val drawerWarmup").substringBefore("companion object").contains("executePendingTransactions()"))
        assertTrue(main.contains("transaction.setMaxLifecycle(cached, Lifecycle.State.STARTED)"))
    }
    @Test fun viewRebuildRemovesOldPagerAndNodeListenersAndHotReturnDoesNotRebindAllCards() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        val source=File(app,"src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val parent=source.substringAfter("override fun onDestroyView()").substringBefore("override fun onDestroy()")
        assertTrue(parent.contains("GroupManager.removeListener(adapter)"))
        val child=source.substringAfter("class GroupFragment : Fragment()").substringAfter("override fun onDestroyView()").substringBefore("override fun onDestroy()")
        assertTrue(child.contains("ProfileManager.removeListener(it)"));assertTrue(child.contains("GroupManager.removeListener(it)"))
        assertTrue(child.contains("configurationListView.adapter = null"))
        assertTrue(source.contains("if (lastAppliedAlwaysShowAddress != DataStore.alwaysShowAddress) refreshAddressDisplayPreferences()"))
    }
}
