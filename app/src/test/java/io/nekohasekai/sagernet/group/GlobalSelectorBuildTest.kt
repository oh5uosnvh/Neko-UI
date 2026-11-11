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
    @Test fun selectorMembershipAndPolicyStayWithinTheSelectedGroup() {
        val a = node("A", "same name")
        val b = node("B", "same name")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertFalse(ca.globalSelector)
        assertEquals(setOf(a.id), ca.profileTagMap.keys)
        assertEquals(setOf(b.id), cb.profileTagMap.keys)
        assertEquals(a.groupId, ca.selectorGroupId)
        assertEquals(b.groupId, cb.selectorGroupId)
        assertNotEquals(ca.selectorFingerprint, cb.selectorFingerprint)
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
        assertEquals(setOf(a.id), ca.profileTagMap.keys)
        assertNotEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertNotEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        val jsonA = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
        val jsonB = com.google.gson.JsonParser.parseString(cb.config).asJsonObject
        assertTrue(jsonA.getAsJsonArray("outbounds").any { it.toString().contains("1.1.1.1") })
        assertTrue(jsonB.getAsJsonArray("outbounds").any { it.toString().contains("9.9.9.9") })
    }

    @Test fun differentDirectDnsStillCompilesOneStableAllGroupsSelector() {
        val a = node("A", "one")
        val b = node("B", "two")
        dns(b, direct = "udp://9.9.9.9")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertEquals(setOf(a.id), ca.profileTagMap.keys)
        assertNotEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertNotEquals(ca.selectorFingerprint, cb.selectorFingerprint)
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
        assertNotEquals(ca.selectorApplicationDnsFingerprint, cb.selectorApplicationDnsFingerprint)
        assertNotEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        val rulesA = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("rules")
        val rulesB = com.google.gson.JsonParser.parseString(cb.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("rules")
        assertFalse(rulesA.any { it.toString().contains("app") && it.toString().contains("example") })
        assertFalse(rulesB.any { it.toString().contains("app") && it.toString().contains("example") })
        val outbounds = com.google.gson.JsonParser.parseString(cb.config).asJsonObject
            .getAsJsonArray("outbounds")
        // Hosts belong to the selected group's node resolver and never leak into
        // another group's application DNS rules.
        assertTrue(outbounds.any { it.toString().contains("203.0.113.7") || it.toString().contains("app.example") })
    }

    @Test fun changedMemberInvalidatesRunningPolicy() {
        val a = node("A", "one")
        val b = node("B", "two")
        val old = buildConfig(a).selectorFingerprint
        b.socksBean!!.serverPort = 2080
        env.database.proxyDao().updateProxy(b)
        assertNotEquals(old, buildConfig(a).selectorFingerprint)
    }
}
