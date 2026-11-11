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
    @Test fun allGroupsSelectorPersistsAndReconnectsImmediately() {
        val source = File(app, "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(source.contains("allGroups.all { it.isSelector }"))
        val body = source.substringAfter("R.id.action_global_no_reload ->").substringBefore("R.id.action_global_mode ->")
        assertTrue(body.contains("group.isSelector = enable"))
        assertTrue(body.contains("SagerNet.reloadService()"))
        assertFalse(body.contains("DataStore.globalSelector = enable"))
        assertTrue(File(app, "src/main/res/values-zh-rCN/global_no_reload.xml").readText().contains(">全分组 selector<"))
    }
}
