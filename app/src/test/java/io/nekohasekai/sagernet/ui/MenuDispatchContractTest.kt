package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Menu actions must be consumed once by the self-drawn popup dispatcher. */
class MenuDispatchContractTest {
    @Test fun configurationMenuConsumesEveryAction() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val handler = source.substringAfter("override fun onMenuItemClick(item: MenuItem): Boolean")
            .substringBefore("private var latencyOverlay")
        assertTrue(handler.contains("return true"))
        assertTrue(handler.contains("return true\n    }"))
        assertTrue(handler.contains("action_new_group"))
        assertTrue(handler.contains("action_import_file"))
    }
}
