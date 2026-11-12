package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class GlobalNoReloadMenuTest {
    private val app = sequenceOf(File("app"), File(".")).first { File(it, "src/main").exists() }
    @Test fun globalNoReloadImmediatelyFollowsAlwaysShowAddress() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/main/res/menu/add_profile_menu.xml"))
        val nodes = document.getElementsByTagName("item")
        val ids = (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:id") }
        val position = ids.indexOf("@+id/action_always_show_address")
        assertTrue(position >= 0)
        assertEquals("@+id/action_global_no_reload", ids[position + 1])
        val item = nodes.item(position + 1) as org.w3c.dom.Element
        assertEquals("true", item.getAttribute("android:checkable"))
    }
    @Test fun toggleIsAPurePersistedOptionWithoutReloadOrGroupWrites() {
        val fragment = File(app, "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        // Menu check state mirrors the persisted option, not the group flags.
        assertTrue(fragment.contains("menu.findItem(R.id.action_global_no_reload)?.isChecked = DataStore.globalSelectorAllGroups"))
        val body = fragment.substringAfter("R.id.action_global_no_reload ->").substringBefore("R.id.action_global_mode ->")
        // The handler only flips the DataStore flag and shows a snackbar.
        assertTrue(body.contains("DataStore.globalSelectorAllGroups = !DataStore.globalSelectorAllGroups"))
        assertTrue(body.contains("R.string.global_no_reload_enabled"))
        assertTrue(body.contains("R.string.global_no_reload_disabled"))
        // No batched group writes, no service reload, no route updater in the handler.
        assertFalse(body.contains("reloadService"))
        assertFalse(body.contains("updateGroup"))
        assertFalse(body.contains("postUpdate"))
        assertFalse(body.contains("RuntimeRouteUpdater"))
        assertFalse(body.contains("isSelector = enable"))
        val constants = File(app, "src/main/java/io/nekohasekai/sagernet/Constants.kt").readText()
        val store = File(app, "src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        assertTrue(constants.contains("GLOBAL_SELECTOR_ALL_GROUPS = \"globalSelectorAllGroups\""))
        assertTrue(store.contains("var globalSelectorAllGroups by configurationStore.boolean(Key.GLOBAL_SELECTOR_ALL_GROUPS)"))
    }
    @Test fun configBuilderCompilesSelectedGroupWhenGlobalOptionEnabled() {
        val builder = File(app, "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        // The option participates in buildSelector: a non-selector group also compiles.
        assertTrue(builder.contains("group?.isSelector == true || globalSelectorAllGroups"))
        // Members come from the selected group only — never a union of all groups.
        assertTrue(builder.contains("SagerDatabase.proxyDao.getByGroup(selectedGroupId)"))
        assertFalse(builder.contains("allGroups.filter"))
        assertFalse(builder.contains("flatMap"))
        // forTest/forExport short-circuits stay in place.
        assertTrue(builder.contains("!forTest && !forExport && DataStore.globalSelectorAllGroups"))
        assertTrue(builder.contains("!disableSelector"))
        assertFalse(builder.contains("allGroups.filter { it.isSelector }.sortedBy"))
        val zh = File(app, "src/main/res/values-zh-rCN/global_no_reload.xml").readText()
        assertTrue(zh.contains(">全分组 selector<"))
        assertTrue(zh.contains("下次连接时生效"))
        val en = File(app, "src/main/res/values/global_no_reload.xml").readText()
        assertTrue(en.contains("Takes effect on next connection."))
        assertFalse(en.contains("Reconnecting now"))
    }
}
