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
        DataStore.enableDnsRouting = true
        DataStore.enableFakeDns = true
        DataStore.globalMode = false
        DataStore.dnsHosts = ""
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

    /**
     * The fixture export is opt-in so normal unit tests stay hermetic. CI can pass
     * -Dneko.dns.fixtureDir=/path and feed these exact App JSON documents to the
     * sing-box 1.14 decoder without changing the production builder.
     */
    private fun exportDnsFixture(name: String, result: io.nekohasekai.sagernet.fmt.ConfigBuildResult) {
        val directory = System.getProperty("neko.dns.fixtureDir")?.takeIf { it.isNotBlank() } ?: return
        val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        java.io.File(directory).apply { mkdirs() }
            .resolve("$safeName.json").writeText(result.config)
    }

    private fun dnsJson(result: io.nekohasekai.sagernet.fmt.ConfigBuildResult) =
        com.google.gson.JsonParser.parseString(result.config).asJsonObject

    private fun assertDns14ServerShape(root: com.google.gson.JsonObject) {
        val dns = root.getAsJsonObject("dns")
        assertFalse("DNSOptions.independent_cache must be absent", dns.has("independent_cache"))
        assertFalse("legacy top-level dns.fakeip must be absent", dns.has("fakeip"))
        dns.getAsJsonArray("servers").forEach { element ->
            val server = element.asJsonObject
            assertTrue("DNS transport type is required: $server", server["type"]?.asString?.isNotBlank() == true)
            assertFalse("legacy DNS server address: $server", server.has("address"))
            assertFalse("legacy DNS server address_resolver: $server", server.has("address_resolver"))
            assertFalse("legacy DNS server strategy: $server", server.has("strategy"))
        }
        dns.getAsJsonArray("rules")?.forEach { element ->
            val outbound = element.asJsonObject["outbound"]
            assertFalse("legacy DNS rule outbound:any: $element",
                outbound?.isJsonArray == true && outbound.asJsonArray.any { it.asString == "any" })
        }
    }

    @Test fun buildConfigForTestUsesDirectFinalAndModernDns14Servers() {
        val a = node("DNS 1.14 test", "for-test")
        DataStore.directDns = "tls://resolver.example:8853"
        DataStore.enableFakeDns = true
        val result = buildConfig(a, forTest = true)
        val root = dnsJson(result)
        val dns = root.getAsJsonObject("dns")

        assertEquals("dns-direct", dns["final"].asString)
        assertEquals("dns-direct", root.getAsJsonObject("route")
            .getAsJsonObject("default_domain_resolver")["server"].asString)
        assertFalse("forTest must not emit fakeip", dns.has("fakeip"))
        assertFalse("forTest must not emit a fakeip server", dns.getAsJsonArray("servers").any {
            it.asJsonObject["type"]?.asString == "fakeip"
        })
        assertDns14ServerShape(root)
        val direct = dns.getAsJsonArray("servers").single { it.asJsonObject["tag"].asString == "dns-direct" }.asJsonObject
        assertEquals("neko-tls", direct["type"].asString)
        assertEquals("resolver.example", direct["server"].asString)
        assertEquals(8853, direct["server_port"].asInt)
        assertEquals("dns-local", direct.getAsJsonObject("domain_resolver")["server"].asString)
        exportDnsFixture("forTest", result)
    }

    @Test fun runtimeBuildConfigUsesFakeipFinalRemoteAndDefaultDomainResolver() {
        val a = node("DNS 1.14 runtime", "runtime")
        DataStore.enableFakeDns = true
        DataStore.globalMode = false
        val result = buildConfig(a)
        val root = dnsJson(result)
        val dns = root.getAsJsonObject("dns")
        val route = root.getAsJsonObject("route")

        assertEquals("dns-remote", dns["final"].asString)
        assertFalse("legacy top-level dns.fakeip must not be emitted", dns.has("fakeip"))
        val fake = dns.getAsJsonArray("servers").single { it.asJsonObject["tag"]?.asString == "dns-fake" }.asJsonObject
        assertEquals("fakeip", fake["type"].asString)
        assertEquals("198.18.0.0/15", fake["inet4_range"].asString)
        assertEquals("fc00::/18", fake["inet6_range"].asString)
        assertTrue(dns.getAsJsonArray("rules").filter { it.asJsonObject["server"]?.asString == "dns-fake" }
            .all { !it.asJsonObject.has("strategy") })
        assertTrue("route.default_domain_resolver is required", route.has("default_domain_resolver"))
        assertEquals("dns-direct", route["default_domain_resolver"].asJsonObject["server"].asString)
        assertTrue(route.getAsJsonObject("default_domain_resolver")["disable_cache"].asBoolean)
        val remote = dns.getAsJsonArray("servers").single { it.asJsonObject["tag"].asString == "dns-remote" }.asJsonObject
        assertEquals("proxy", remote["detour"].asString)
        assertDns14ServerShape(root)
        exportDnsFixture("runtime-fakeip", result)
    }

    @Test fun subscriptionSelectorPreservesModernPolicyDispatchAndCustomTcpTlsServers() {
        val a = node("DNS selector", "selector")
        dns(a,
            server = "tcp://resolver.example:8080\ntls://192.0.2.53:853",
            direct = "https://1.1.1.1/dns-query",
            hosts = "hosts:\n  node.example: 192.0.2.77\n")
        DataStore.enableFakeDns = true
        DataStore.globalMode = false
        val result = buildConfig(a)
        val root = dnsJson(result)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }
        val types = servers.mapNotNull { it["type"]?.asString }

        assertTrue("the selected group must compile a selector outbound", result.globalSelector)
        assertTrue("subscription DNS selector dispatch missing", types.contains("neko-selector"))
        assertTrue("subscription policy transport missing", types.contains("neko-policy"))
        assertTrue("custom TCP DNS transport was not retained", types.contains("neko-tcp"))
        assertTrue("custom TLS DNS transport was not retained", types.contains("neko-tls"))
        val tcp = servers.single { it["type"]?.asString == "neko-tcp" }
        assertEquals("resolver.example", tcp["server"].asString)
        assertEquals(8080, tcp["server_port"].asInt)
        assertEquals("dns-local", tcp["domain_resolver"].asString)
        assertDns14ServerShape(root)
        exportDnsFixture("subscription-selector-tcp-tls", result)
    }

    @Test fun blockedDnsRuleUsesPredefinedNoErrorWithoutAResolverServer() {
        val a = node("DNS block", "blocked")
        DataStore.enableFakeDns = false
        DataStore.globalMode = false
        env.database.rulesDao().createRule(RuleEntity(
            name = "DNS block", enabled = true, domains = "full:block.test", outbound = -2L
        ))
        val result = buildConfig(a)
        val root = dnsJson(result)
        val dnsRules = root.getAsJsonObject("dns").getAsJsonArray("rules").map { it.asJsonObject }
        val blocked = dnsRules.single { rule ->
            rule["domain"]?.asJsonArray?.any { it.asString == "block.test" } == true
        }
        assertEquals("predefined", blocked["action"].asString)
        assertEquals("NOERROR", blocked["rcode"].asString)
        assertFalse("predefined block rule must not name a DNS server", blocked.has("server"))
        assertFalse("predefined action does not accept disable_cache", blocked.has("disable_cache"))
        assertDns14ServerShape(root)
        exportDnsFixture("block-predefined", result)
    }

    @Test fun localDirectAndRemoteDoNotLeakNetworkOnlyOptions() {
        val a = node("local", "local-dns")
        DataStore.directDns = "local"
        DataStore.remoteDns = "local"
        DataStore.enableFakeDns = false
        val result = buildConfig(a, disableSelector = true)
        val root = dnsJson(result)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        listOf("dns-local", "dns-direct", "dns-remote").forEach { tag ->
            val server = servers.single { it.asJsonObject["tag"].asString == tag }.asJsonObject
            assertEquals("local", server["type"].asString)
            assertFalse(server.has("server"))
            assertFalse(server.has("server_port"))
            assertFalse(server.has("domain_resolver"))
            assertFalse(server.has("domain_strategy"))
        }
        assertDns14ServerShape(root)
        exportDnsFixture("runtime-local", result)
    }

    @Test fun nonSelectorSubscriptionOverrideAndHostsUseModernDns() {
        val a = node("subscription", "single-node")
        dns(a, server = "tcp://resolver.example:5353", direct = "tls://192.0.2.53:8853")
        DataStore.dnsHosts = "app.example 203.0.113.8"
        DataStore.enableFakeDns = false
        val result = buildConfig(a, disableSelector = true)
        val root = dnsJson(result)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }
        assertEquals("neko-policy", servers.single { it["tag"].asString == "dns-direct" }["type"].asString)
        assertTrue(servers.any { it["type"]?.asString == "neko-tls" && it["server_port"].asInt == 8853 })
        val hosts = servers.single { it["tag"].asString == "dns-hosts" }
        assertEquals("hosts", hosts["type"].asString)
        assertEquals("203.0.113.8", hosts.getAsJsonObject("predefined").getAsJsonArray("app.example").single().asString)
        val hostsRule = root.getAsJsonObject("dns").getAsJsonArray("rules").single {
            it.asJsonObject["server"]?.asString == "dns-hosts"
        }.asJsonObject
        assertEquals("app.example", hostsRule.getAsJsonArray("domain").single().asString)
        assertFalse(hostsRule.has("ip_accept_any"))
        assertDns14ServerShape(root)
        exportDnsFixture("subscription-single-hosts", result)
    }
}
