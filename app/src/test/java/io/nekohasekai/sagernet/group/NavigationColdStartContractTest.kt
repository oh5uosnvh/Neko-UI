package io.nekohasekai.sagernet.group

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Pure contracts supplement runtime frame/cancel/outline tests without starting the VPN app. */
class NavigationColdStartContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private fun source(name: String) = File(app, "src/main/java/io/nekohasekai/sagernet/ui/$name.kt").readText()

    @Test fun hamburgerHasThreeEqualBarsRatherThanTheOldDescendingPaths() {
        for (icon in listOf("ic_navigation_menu", "ic_topbar_drawer")) {
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(app, "src/main/res/drawable/$icon.xml"))
        val paths = xml.getElementsByTagName("path")
        assertEquals(1, paths.length)
        val path = paths.item(0) as org.w3c.dom.Element
        val data = path.getAttributeNS("http://schemas.android.com/apk/res/android", "pathData")
        val bars = Regex("M(\\d+),(\\d+)h(\\d+)v(\\d+)H(\\d+)z").findAll(data).toList()
        assertEquals(3, bars.size)
        assertEquals(data, bars.joinToString("") { it.value })
        assertEquals(listOf(6, 11, 16), bars.map { it.groupValues[2].toInt() })
        bars.forEach {
            assertEquals("3", it.groupValues[1])
            assertEquals("18", it.groupValues[3])
            assertEquals("2", it.groupValues[4])
            assertEquals("3", it.groupValues[5])
        }
        }
    }

    @Test fun navigationDoesNotPreinflateOtherPagesOrFlushAllFragmentTransactions() {
        val main = source("MainActivity")
        assertFalse(main.contains("DRAWER_WARM_IDS"))
        assertFalse(main.contains("drawerWarmup"))
        assertFalse(main.contains("executePendingTransactions()"))
        assertFalse(main.contains(".commitNow()"))
        assertTrue(main.contains("pageSlide.prepare(binding.fragmentHolder"))
        assertTrue(main.contains("NavigationFrameGate.isPageReady(fragment.view)"))
        assertTrue(main.contains("generation == navigationGeneration"))
        assertTrue(main.contains("setMaxLifecycle(old, Lifecycle.State.STARTED)"))
        assertTrue(main.contains("transaction.hide(old)"))
        assertTrue(main.contains("transaction.show(fragment)"))
        assertFalse(main.contains("resetScrollState()"))
    }

    @Test fun stopSaveDestroyAndReopenedMenuCancelDeferredCommits() {
        val main = source("MainActivity")
        val cancel = main.substringAfter("private fun cancelNavigationWork()").substringBefore("override fun onStop()")
        assertTrue(cancel.contains("cancelPendingPageNavigation()"))
        assertTrue(cancel.contains("pageSlide.cancel()"))
        for (method in listOf("onStop()", "onDestroy()", "onSaveInstanceState(outState: Bundle)")) {
            assertTrue(main.substringAfter("override fun $method").substringBefore("\n    }")
                .contains("cancelNavigationWork()"))
        }
        assertTrue(main.substringAfter("fun openNavigationMenu(").substringBefore("override fun onKeyDown")
            .contains("cancelPendingPageNavigation()"))
        assertTrue(source("PageSlideTransition").contains("const val DURATION_MS = 260L"))
    }
}
