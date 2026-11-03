package io.nekohasekai.sagernet.group

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SearchAndAddressHostContractTest {
    private val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
    private val config=File(app,"src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
    @Test fun searchHintUsesDayNightForegroundAndScopesUseRealSeparateSources() {
        val top=File(app,"src/main/java/io/nekohasekai/sagernet/ui/TopBarController.kt").readText()
        val search=top.substringAfter("private fun configureSearchField").substringBefore("private fun resolveForeground")
        assertTrue(search.contains("setHintTextColor(iconColor)"))
        assertFalse(search.contains("0x99FFFFFF"))
        val filter=config.substringAfter("fun filter(name: String, global: Boolean").substringBefore("fun move(from")
        assertTrue(filter.contains("if (query.isGlobal) SagerDatabase.proxyDao.getAll()"))
        assertTrue(filter.contains("else PendingProfileDeletes.loadGroup(query.groupId)"))
        assertTrue(filter.contains("batchLoadGeneration.accepts(request)"))
        assertFalse(filter.contains("configurationList.values.filter"))
    }
    @Test fun addressShortcutIsImmediatelyBelowGlobalModeAndReusesTheRealPreference() {
        val doc=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder()
            .parse(File(app,"src/main/res/menu/add_profile_menu.xml"))
        val nodes=doc.getElementsByTagName("item")
        val ns="http://schemas.android.com/apk/res/android"
        val ids=(0 until nodes.length).map{(nodes.item(it) as org.w3c.dom.Element).getAttributeNS(ns,"id")}
        assertEquals(ids.indexOf("@+id/action_global_mode")+1,ids.indexOf("@+id/action_always_show_address"))
        assertTrue(config.contains("menu.findItem(R.id.action_always_show_address)?.isChecked = DataStore.alwaysShowAddress"))
        assertTrue(config.contains("DataStore.alwaysShowAddress ="))
        assertTrue(config.contains("refreshAddressDisplayPreferences()"))
    }
}
