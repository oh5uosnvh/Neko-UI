package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConfigurationExportSnackbarTest {
    @Test fun configurationTagExportUsesMainAnchorButGroupPageOriginalExportIsUntouched() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        val export=File(app,"src/main/java/io/nekohasekai/sagernet/ui/GroupProfileExport.kt").readText()
        val result=export.substringAfter("private fun showResult(message: Int)").substringBefore("internal companion object")
        assertTrue(result.contains("fragment is ConfigurationFragment && activity != null"))
        assertTrue(result.contains("activity.snackbar(message)"))
        assertTrue(result.contains("Snackbar.make(view, message"))
        val main=File(app,"src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
        val snackbar=main.substringAfter("override fun snackbarInternal").substringBefore("override fun stateChanged")
        assertTrue(snackbar.contains("anchorView = binding.controlBar"))
        assertTrue(snackbar.contains("R.color.control_bar_surface"))
        assertTrue(snackbar.contains("R.color.control_bar_text"))
    }
}
