package io.nekohasekai.sagernet.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Focused source contracts for the cross-group selector safety boundary. */
class GlobalSelectorContractTest {
    private fun source(path: String): String = sequenceOf(
        File("app/src/main/$path"), File("src/main/$path")
    ).first { it.exists() }.readText()

    @Test fun settingIsPersistedAndExposedWithoutChangingGroupSelector() {
        val constants = source("java/io/nekohasekai/sagernet/Constants.kt")
        val store = source("java/io/nekohasekai/sagernet/database/DataStore.kt")
        val prefs = source("res/xml/global_preferences.xml")
        val settings = source("java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        assertTrue(constants.contains("GLOBAL_SELECTOR = \"globalSelector\""))
        assertTrue(store.contains("var globalSelector by configurationStore.boolean(Key.GLOBAL_SELECTOR)"))
        assertTrue(prefs.contains("app:key=\"globalSelector\""))
        assertTrue(settings.contains("findPreference<SwitchPreference>(Key.GLOBAL_SELECTOR)"))
    }

    @Test fun builderCompilesAllGroupsAndKeepsGroupDnsPolicyExplicit() {
        val builder = source("java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt")
        assertTrue(builder.contains("DataStore.globalSelector"))
        assertTrue(builder.contains("SagerDatabase.groupDao.allGroups()"))
        assertTrue(builder.contains("Global selector cannot mix subscription DNS policies"))
        assertTrue(builder.contains("it.resolveChain()"))
        assertTrue(builder.contains("selectorFingerprint"))
    }

    @Test fun liveSwitchRejectsStaleGlobalMembershipInsteadOfReloading() {
        val service = source("java/io/nekohasekai/sagernet/bg/BaseService.kt")
        val aidl = sequenceOf(
            File("app/src/main/aidl/io/nekohasekai/sagernet/aidl/ISagerNetService.aidl"),
            File("src/main/aidl/io/nekohasekai/sagernet/aidl/ISagerNetService.aidl")
        ).first { it.exists() }.readText()
        assertTrue(aidl.contains("String selectSelectorNode(long profileId)"))
        val selectBody = service.substringAfter("override fun selectSelectorNode").substringBefore("private fun selectorError")
        assertTrue(selectBody.contains("STALE_CONFIG"))
        assertFalse(selectBody.contains("stopRunner"))
        assertFalse(selectBody.contains("reload()"))
        assertTrue(service.contains("rebuilt.selectorFingerprint == running.config.selectorFingerprint"))
        assertTrue(service.contains("box.selectOutbound(tag)"))
        assertTrue(service.contains("selector rejected compiled node"))
    }

    @Test fun nativeBoolAndRunningBoxAreRequired() {
        val go = sequenceOf(File("libcore/box.go"), File("../libcore/box.go"))
            .first { it.exists() }.readText()
        val bridge = source("java/moe/matsuri/nb4a/NativeInterface.kt")
        assertTrue(go.contains("b.state != 1 || b.selector == nil"))
        assertTrue(go.contains("return b.selector.SelectOutbound(tag)"))
        assertTrue(bridge.contains("running.config.trafficMap[tag]"))
        assertTrue(bridge.contains("reject selector callback outside current compiled membership"))
        assertFalse(bridge.contains("DataStore.selectedProxy = id"))
    }
}
