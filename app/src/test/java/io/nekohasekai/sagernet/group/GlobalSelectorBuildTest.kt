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

/**
 * Real DB + real builder. The compiled runtime selector ALWAYS contains only
 * the selected group's own nodes (per-group switch gates it). Startup never
 * pays for the whole node catalog.
 */
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
    private fun addToGroup(groupId: Long, name: String): ProxyEntity {
        val bean = SOCKSBean().apply {
            initializeDefaultValues(); this.name = name; serverAddress = "192.0.2.1"; serverPort = 1080
        }
        return ProxyEntity(groupId = groupId).apply {
            putBean(bean); id = env.database.proxyDao().addProxy(this)
        }
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

    @Test fun compiledSelectorContainsOnlyTheSelectedGroupsNodes() {
        val a = node("A", "a-one")
        val b = node("B", "b-two")
        val ca = buildConfig(a)
        assertTrue(ca.globalSelector)
        assertEquals(setOf(a.id), ca.profileTagMap.keys)
        // Building from the other group compiles only that group's node.
        val cb = buildConfig(b)
        assertTrue(cb.globalSelector)
        assertEquals(setOf(b.id), cb.profileTagMap.keys)
    }

    @Test fun nonSelectorGroupNeverCompilesASelector() {
        val a = node("A", "a-one")
        val ga = env.database.groupDao().getById(a.groupId)!!
        ga.isSelector = false; env.database.groupDao().updateGroup(ga)
        assertFalse(buildConfig(a).globalSelector)
        assertEquals(-1L, buildConfig(a).selectorGroupId)
    }

    @Test fun otherGroupsNodesNeverJoinTheCompiledGraph() {
        val a = node("A", "a-one")
        val b = node("B", "b-two")
        val c = addToGroup(a.groupId, "a-three")
        val ca = buildConfig(a)
        assertEquals(setOf(a.id, c.id), ca.profileTagMap.keys)
        assertFalse(ca.profileTagMap.containsKey(b.id))
        val json = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
        val selector = json.getAsJsonArray("outbounds").single {
            it.asJsonObject["tag"].asString == "proxy"
        }.asJsonObject
        val memberTags = selector.getAsJsonArray("outbounds").map { it.asString }
        assertEquals(ca.profileTagMap.values.toList(), memberTags)
    }

    @Test fun selectorGraphIsStableAcrossRebuildsOfTheSameGroup() {
        val a = node("A", "one")
        val c = addToGroup(a.groupId, "three")
        val ca = buildConfig(a)
        val cb = buildConfig(c)
        assertEquals(ca.profileTagMap, cb.profileTagMap)
        assertEquals(ca.selectorGroupId, cb.selectorGroupId)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
    }

    @Test fun unrelatedServerDnsDifferencesStayGroupScoped() {
        val a = node("A", "same name")
        val b = node("饿饭sunny", "same name")
        dns(a, server = "udp://1.1.1.1")
        dns(b, server = "udp://9.9.9.9")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertEquals(setOf(a.id), ca.profileTagMap.keys)
        assertEquals(setOf(b.id), cb.profileTagMap.keys)
        val serversA = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("servers")
        assertTrue(serversA.any { it.toString().contains("1.1.1.1") })
        assertFalse(serversA.any { it.toString().contains("9.9.9.9") })
        assertTrue(ca.config.isNotBlank())
        assertTrue(cb.config.isNotBlank())
    }

    @Test fun applicationHostsInjectAsSelectedGroupPolicy() {
        val a = node("A", "one")
        dns(a, hosts = "hosts:\n  app.example: 203.0.113.7\n")
        val ca = buildConfig(a)
        val servers = com.google.gson.JsonParser.parseString(ca.config).asJsonObject
            .getAsJsonObject("dns").getAsJsonArray("servers")
        val dispatch = servers.single { it.asJsonObject["tag"].asString == "dns-selector-general" }.asJsonObject
        val choices = dispatch.getAsJsonObject("choices")
        val choice = choices[ca.profileTagMap.getValue(a.id)].asString
        assertTrue("unexpected choice $choice", choice == "dns-remote" || choice.startsWith("dns-sub-"))
        assertTrue(servers.any { it.toString().contains("203.0.113.7") })
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

    @Test fun disabledGroupDoesNotJoinAndReorderingDoesNotInvalidateGraph() {
        val a = node("A", "one"); val c = addToGroup(a.groupId, "three")
        val before = buildConfig(a)
        val ga = env.database.groupDao().getById(a.groupId)!!
        c.userOrder = -100; env.database.proxyDao().updateProxy(c)
        assertEquals(before.profileTagMap.keys, buildConfig(a).profileTagMap.keys)
        // Per-group switch off: no runtime selector.
        ga.isSelector = false; env.database.groupDao().updateGroup(ga)
        assertFalse(buildConfig(a).globalSelector)
    }

    @Test fun frontAndLandingProxyEditsInvalidateButSelectionDoesNot() {
        val a = node("A", "one"); val c = addToGroup(a.groupId, "three")
        val front = node("Hop", "front")
        val ga = env.database.groupDao().getById(a.groupId)!!
        ga.frontProxy = front.id; env.database.groupDao().updateGroup(ga)
        val before = buildConfig(a)
        assertEquals(before.selectorFingerprint, buildConfig(c).selectorFingerprint)
        assertTrue(before.config.contains("detour"))
        front.socksBean!!.serverPort = 2222; env.database.proxyDao().updateProxy(front)
        assertNotEquals(before.selectorFingerprint, buildConfig(c).selectorFingerprint)
        ga.landingProxy = c.id; env.database.groupDao().updateGroup(ga)
        assertEquals(buildConfig(a).selectorFingerprint, buildConfig(c).selectorFingerprint)
    }

    @Test fun fixedNodeRoutesDoNotTurnIntoCurrentSelectorWhenThatNodeIsSelected() {
        val a = node("A", "one"); val c = addToGroup(a.groupId, "three")
        DataStore.globalMode = false
        env.database.rulesDao().createRule(RuleEntity(name = "Fixed A", domains = "full:fixed.test", enabled = true, outbound = a.id))
        val ca = buildConfig(a); val cc = buildConfig(c)
        assertEquals(ca.selectorFingerprint, cc.selectorFingerprint)
        val routes = com.google.gson.JsonParser.parseString(ca.config).asJsonObject.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(ca.profileTagMap[a.id], routes.single { it.toString().contains("fixed.test") }.asJsonObject["outbound"].asString)
    }

    @Test fun singleNodeFallbackAndExportNeverPretendToBeGlobalSelector() {
        val a = node("A", "one"); addToGroup(a.groupId, "three")
        assertFalse(buildConfig(a, disableSelector = true).globalSelector)
        assertEquals(setOf(a.id), buildConfig(a, disableSelector = true).profileTagMap.keys)
        assertEquals(-1L, buildConfig(a, forExport = true).selectorGroupId)
    }

    @Test fun changedMemberInvalidatesRunningPolicy() {
        val a = node("A", "one")
        val old = buildConfig(a).selectorFingerprint
        a.socksBean!!.serverPort = 2080
        env.database.proxyDao().updateProxy(a)
        assertNotEquals(old, buildConfig(a).selectorFingerprint)
    }
}
