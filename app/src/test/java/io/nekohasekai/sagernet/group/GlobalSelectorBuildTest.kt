package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real DB + real builder, not source-text or manufactured fingerprint assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class GlobalSelectorBuildTest {
    private lateinit var env: QuickPopupTestEnvironment
    @Before fun setup() {
        env = QuickPopupTestEnvironment()
        DataStore.globalSelector = false
        DataStore.globalCustomConfig = ""
        DataStore.remoteDns = "https://1.1.1.1/dns-query"
        DataStore.directDns = "https://8.8.8.8/dns-query"
    }
    @After fun cleanup() { env.close() }
    private fun node(groupName: String, name: String): ProxyEntity {
        val group = ProxyGroup(name = groupName, isSelector = true)
        group.id = env.database.groupDao().createGroup(group)
        val bean = SOCKSBean().apply {
            initializeDefaultValues(); this.name = name; serverAddress = "192.0.2.1"; serverPort = 1080
        }
        return ProxyEntity(groupId = group.id).apply {
            putBean(bean); id = env.database.proxyDao().addProxy(this)
        }
    }
    @Test fun selectorMembershipAndPolicyAreIdenticalAcrossEnabledManualGroups() {
        val a = node("A", "same name")
        val b = node("B", "same name")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertTrue(ca.globalSelector)
        assertEquals(setOf(a.id, b.id), ca.profileTagMap.keys)
        assertEquals(ca.profileTagMap, cb.profileTagMap)
        assertEquals(io.nekohasekai.sagernet.fmt.GLOBAL_SELECTOR_GROUP_ID, ca.selectorGroupId)
        assertEquals(ca.selectorGroupId, cb.selectorGroupId)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        DataStore.remoteDns = "https://9.9.9.9/dns-query"
        assertNotEquals(ca.selectorFingerprint, buildConfig(a).selectorFingerprint)
    }
    @Test fun customRemoteRulesReachRealBuilderWhilePendingSourceIsNotCatchAll() {
        val a = node("A", "one")
        DataStore.globalMode = false
        val ready = RuleEntity(name = "Downloaded", enabled = true, domains = "full:manual.test",
            sourceUrl = "https://example.com/r.yaml", remoteDomains = "domain:microsoft.com",
            remoteIps = "192.0.2.0/24", cachedRuleCount = 2, outbound = -1)
        val pending = RuleEntity(name = "Pending", enabled = true, sourceUrl = "https://example.com/p.yaml", outbound = -2)
        env.database.rulesDao().createRule(ready)
        env.database.rulesDao().createRule(pending)
        val json = com.google.gson.JsonParser.parseString(buildConfig(a).config).asJsonObject
        val rules = json.getAsJsonObject("route").getAsJsonArray("rules")
        assertTrue(rules.any { it.toString().contains("microsoft.com") && it.toString().contains("192.0.2.0/24") })
        assertTrue(rules.any { it.toString().contains("manual.test") })
        assertFalse(rules.any { it.toString() == "{\"action\":\"reject\"}" })
    }

    private fun dns(node: ProxyEntity, server: String = "", direct: String = "", hosts: String = "") {
        val group = env.database.groupDao().getById(node.groupId)!!
        group.subscription = SubscriptionBean().apply {
            initializeDefaultValues()
            serverDnsResolver = server
            directDnsResolver = direct
            hostsConfig = hosts
        }
        env.database.groupDao().updateGroup(group)
    }

    @Test fun unrelatedServerDnsDifferencesDoNotAbortStartupOrBlockCompatibleSwitches() {
        val a = node("A", "same name")
        val b = node("饿饭sunny", "same name")
        dns(a, server = "udp://1.1.1.1")
        dns(b, server = "udp://9.9.9.9")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertEquals(setOf(a.id, b.id), ca.profileTagMap.keys)
        assertEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        assertTrue(ca.config.isNotBlank())
        assertTrue(cb.config.isNotBlank())
    }

    @Test fun differentDirectDnsStillCompilesOneStableAllGroupsSelector() {
        val a = node("A", "one")
        val b = node("B", "two")
        dns(b, direct = "udp://9.9.9.9")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertEquals(setOf(a.id, b.id), ca.profileTagMap.keys)
        assertEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        val config = com.google.gson.JsonParser.parseString(cb.config).asJsonObject
        val servers = config.getAsJsonObject("dns").getAsJsonArray("servers")
        assertTrue(servers.any { it.toString().contains("9.9.9.9") })
    }

    @Test fun applicationHostsAreNotInjectedAsOneSelectedGroupPolicy() {
        val a = node("A", "one")
        val b = node("B", "two")
        dns(b, hosts = "hosts:\n  app.example: 203.0.113.7\n")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        val rulesA = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("rules")
        val rulesB = com.google.gson.JsonParser.parseString(cb.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("rules")
        assertEquals(rulesA, rulesB)
        assertTrue(rulesB.any { it.toString().contains("app") && it.toString().contains("example") && it.toString().contains("dns-selector-general") })
        val servers = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("servers")
        val dispatch = servers.single { it.asJsonObject["tag"].asString == "dns-selector-general" }.asJsonObject
        val choices = dispatch.getAsJsonObject("choices")
        assertEquals("dns-remote", choices[ca.profileTagMap.getValue(a.id)].asString)
        val bResolver = choices[ca.profileTagMap.getValue(b.id)].asString
        assertTrue(servers.single { it.asJsonObject["tag"].asString == bResolver }.toString().contains("203.0.113.7"))
        assertTrue(rulesB.filter { it.toString().contains("dns-selector-general") }.all { it.asJsonObject["disable_cache"].asBoolean })
    }

    @Test fun disabledGroupDoesNotJoinAndReorderingDoesNotInvalidateGraph() {
        val a = node("A", "one"); val b = node("B", "two")
        val before = buildConfig(a)
        val gb = env.database.groupDao().getById(b.groupId)!!
        gb.userOrder = -10; env.database.groupDao().updateGroup(gb)
        b.userOrder = -100; env.database.proxyDao().updateProxy(b)
        assertEquals(before.selectorFingerprint, buildConfig(b).selectorFingerprint)
        gb.isSelector = false; env.database.groupDao().updateGroup(gb)
        assertEquals(setOf(a.id), buildConfig(a).profileTagMap.keys)
        assertNotEquals(before.selectorFingerprint, buildConfig(a).selectorFingerprint)
        assertFalse(buildConfig(b).globalSelector)
    }

    @Test fun frontAndLandingProxyEditsInvalidateButSelectionDoesNot() {
        val a = node("A", "one"); val b = node("B", "two")
        val front = node("Hop", "front")
        val ga = env.database.groupDao().getById(a.groupId)!!
        ga.frontProxy = front.id; env.database.groupDao().updateGroup(ga)
        val before = buildConfig(a)
        assertEquals(before.selectorFingerprint, buildConfig(b).selectorFingerprint)
        assertTrue(before.config.contains("detour"))
        front.socksBean!!.serverPort = 2222; env.database.proxyDao().updateProxy(front)
        assertNotEquals(before.selectorFingerprint, buildConfig(b).selectorFingerprint)
        ga.landingProxy = b.id; env.database.groupDao().updateGroup(ga)
        assertEquals(buildConfig(a).selectorFingerprint, buildConfig(b).selectorFingerprint)
    }

    @Test fun fixedNodeRoutesDoNotTurnIntoCurrentSelectorWhenThatNodeIsSelected() {
        val a = node("A", "one"); val b = node("B", "two")
        DataStore.globalMode = false
        env.database.rulesDao().createRule(RuleEntity(name = "Fixed A", domains = "full:fixed.test", enabled = true, outbound = a.id))
        val ca = buildConfig(a); val cb = buildConfig(b)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        val routes = com.google.gson.JsonParser.parseString(ca.config).asJsonObject.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(ca.profileTagMap[a.id], routes.single { it.toString().contains("fixed.test") }.asJsonObject["outbound"].asString)
    }

    @Test fun singleNodeFallbackAndExportNeverPretendToBeGlobalSelector() {
        val a = node("A", "one"); node("B", "two")
        assertFalse(buildConfig(a, disableSelector = true).globalSelector)
        assertEquals(setOf(a.id), buildConfig(a, disableSelector = true).profileTagMap.keys)
        assertEquals(-1L, buildConfig(a, forExport = true).selectorGroupId)
    }

    @Test fun changedMemberInvalidatesRunningPolicy() {
        val a = node("A", "one")
        val b = node("B", "two")
        val old = buildConfig(a).selectorFingerprint
        a.socksBean!!.serverPort = 2080
        env.database.proxyDao().updateProxy(a)
        assertNotEquals(old, buildConfig(a).selectorFingerprint)
    }
}
