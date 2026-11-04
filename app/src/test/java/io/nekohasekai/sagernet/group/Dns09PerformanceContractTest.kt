package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Dns09PerformanceContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private fun source(name: String) = File(app, "src/main/java/io/nekohasekai/sagernet/ui/$name.kt").readText()

    @Test fun startupWarmsBoundedSidebarPagesWithinTwoSeconds() {
        val main = source("MainActivity")
        assertTrue(main.contains("DRAWER_WARM_START_MS = 240L"))
        assertTrue(main.contains("DRAWER_WARM_INTERVAL_MS = 250L"))
        assertTrue(main.contains("setMaxLifecycle(fragment, Lifecycle.State.STARTED)"))
    }

    @Test fun sidebarSwitchStaysHotAndCommitsImmediately() {
        val main = source("MainActivity")
        assertFalse("cached pages must not be detached back to cold", main.contains("detach(cached)"))
        assertFalse("navigation must not defer commits until drawer close", main.contains("pendingCommit"))
        assertTrue(main.contains("transaction.hide(cached)"))
        assertTrue(main.contains("transaction.setMaxLifecycle(cached, Lifecycle.State.STARTED)"))
    }

    @Test fun nodeSelectionNeverDropsTheReloadDuringConnecting() {
        val config = source("ConfigurationFragment")
        assertFalse("tryLock-or-skip must not come back", config.contains("reloadAccess.tryLock()"))
        assertTrue(config.contains("DataStore.selectedProxy == proxyEntity.id"))
        assertTrue(config.contains("delay(100)"))
    }
}
