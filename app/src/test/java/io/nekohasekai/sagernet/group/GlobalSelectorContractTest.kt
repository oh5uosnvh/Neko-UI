package io.nekohasekai.sagernet.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contracts: the global option toggles per-group no-reload selectors;
 * it never unions all groups' nodes into one compiled selector. */
class GlobalSelectorContractTest {
    private fun source(path: String): String = sequenceOf(
        File("app/src/main/$path"), File("src/main/$path")
    ).first { it.exists() }.readText()

    @Test fun settingIsPersistedAndExposedWithoutChangingGroupSelector() {
        val constants = source("java/io/nekohasekai/sagernet/Constants.kt")
        val store = source("java/io/nekohasekai/sagernet/database/DataStore.kt")
        val prefs = source("res/xml/global_preferences.xml")
        val settings = source("java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        // The persisted key remains only for migration compatibility; there is
        // no second settings-page runtime switch. The toolbar batch action is the
        // sole global convenience entry point.
        assertTrue(constants.contains("GLOBAL_SELECTOR = \"globalSelector\""))
        assertTrue(store.contains("var globalSelector by configurationStore.boolean(Key.GLOBAL_SELECTOR)"))
        assertFalse(prefs.contains("app:key=\"globalSelector\""))
        assertFalse(settings.contains("findPreference<SwitchPreference>(Key.GLOBAL_SELECTOR)"))
    }

    @Test fun selectorGraphIsSelectedGroupOnlyAndToolbarTogglesAPurePersistedOption() {
        val builder = source("java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt")
        val fragment = source("java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        assertTrue(builder.contains("group?.isSelector == true || globalSelectorAllGroups"))
        // Members are the SELECTED group only: the global option must never
        // union every group's nodes into one giant selector (slow cold start).
        assertFalse(builder.contains("allGroups.filter"))
        assertFalse(builder.contains("flatMap"))
        assertTrue(builder.contains("SagerDatabase.proxyDao.getByGroup(selectedGroupId)"))
        assertTrue(builder.contains("GLOBAL_SELECTOR_GROUP_ID else -1L"))
        assertFalse(Regex("DataStore\\.globalSelector(?![A-Za-z])").containsMatchIn(builder))
        assertFalse(builder.contains("Global selector does not support"))
        // The menu action is a pure persisted option: no batched group writes, no reload.
        assertTrue(fragment.contains("DataStore.globalSelectorAllGroups = !DataStore.globalSelectorAllGroups"))
        assertFalse(fragment.contains("group.isSelector = enable"))
        assertFalse(fragment.contains("全部分组启用 selector"))
        assertTrue(builder.contains("selectorFingerprint"))
        assertTrue(builder.contains("interrupt_exist_connections = true"))
    }

    @Test fun liveSwitchAcknowledgesCoreAndAutomaticallyReconnectsIncompatibleGraphs() {
        val service = source("java/io/nekohasekai/sagernet/bg/BaseService.kt")
        val aidl = sequenceOf(
            File("app/src/main/aidl/io/nekohasekai/sagernet/aidl/ISagerNetService.aidl"),
            File("src/main/aidl/io/nekohasekai/sagernet/aidl/ISagerNetService.aidl")
        ).first { it.exists() }.readText()
        assertTrue(aidl.contains("String selectSelectorNode(long profileId)"))
        val selectBody = service.substringAfter("override fun selectSelectorNode")
            .substringBefore("private fun selectorError")
            .replace(Regex("(?s)/\\*.*?\\*/|//[^\\n]*"), "")
        assertFalse(selectBody.contains("STALE_CONFIG"))
        assertTrue(selectBody.contains("reconnectSelector"))
        assertTrue(selectBody.contains("stopRunner(restart = true)"))
        assertTrue(selectBody.contains("put(\"ack\", false).put(\"accepted\", true)"))
        assertTrue(selectBody.contains("put(\"mode\", \"hot\")"))
        assertTrue(selectBody.contains("put(\"mode\", \"reload\")"))
        // Missing tags fall back immediately to automatic reconnect; no synchronous
        // disableSelector preflight is allowed on the Binder click path.
        assertFalse(selectBody.contains("buildConfig("))
        assertTrue(service.contains("rebuilt.selectorFingerprint == running.config.selectorFingerprint"))
        assertTrue(service.contains("box.selectOutbound(tag)"))
        assertTrue(service.contains("selector rejected compiled node"))
    }

    @Test fun compiledGraphNormalizesOnlyAllocatedPortsAndScopesNodeDns() {
        val builder = source("java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt")
        assertTrue(builder.contains("SelectorFingerprint.policy(configMap, runtimePorts)"))
        assertTrue(builder.contains("selectorDns.nodeFallback(nodeDnsConfig, nodeSubscription)"))
        assertTrue(builder.contains("selectorDns.dispatch(\"dns-direct\", selectorDirectChoices)"))
        assertTrue(builder.contains("rule.disable_cache = true"))
        assertTrue(builder.contains("hop.requireBean().clone()"))
        val fragment = source("java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        assertTrue(fragment.contains("receipt.optBoolean(\"accepted\")"))
        assertTrue(fragment.contains("receipt.optBoolean(\"reloading\")"))
    }

    @Test fun nativeBoolAndRunningBoxAreRequired() {
        val go = sequenceOf(File("libcore/box.go"), File("../libcore/box.go"))
            .first { it.exists() }.readText()
        val bridge = source("java/moe/matsuri/nb4a/NativeInterface.kt")
        assertTrue(go.contains("b.state != 1 || b.selector == nil"))
        assertTrue(go.contains("return b.selector.SelectOutbound(tag)"))
        assertTrue(bridge.contains("running.config.trafficMap[tag]"))
        assertTrue(bridge.contains("reject selector callback outside current compiled membership"))
        assertTrue(bridge.contains("DataStore.selectedProxy = id"))
        assertTrue(bridge.contains("selectedOutbound()"))
    }
}
