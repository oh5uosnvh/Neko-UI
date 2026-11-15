package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real DB + real builder coverage for the pre25, per-group selector contract.
 *
 * This class also exports the six complete App JSON documents consumed by
 * buildScript/check-dns14.sh and libcore/dnspolicy/schema14_test.go.  The
 * export is opt-in so ordinary unit tests remain hermetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class GlobalSelectorBuildTest {
    private lateinit var env: QuickPopupTestEnvironment

    @Before fun setup() {
        env = QuickPopupTestEnvironment()
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
            initializeDefaultValues()
            this.name = name
            serverAddress = "192.0.2.1"
            serverPort = 1080
        }
        return ProxyEntity(groupId = group.id).apply {
            putBean(bean)
            id = env.database.proxyDao().addProxy(this)
        }
    }

    private fun addToGroup(groupId: Long, name: String): ProxyEntity {
        val bean = SOCKSBean().apply {
            initializeDefaultValues()
            this.name = name
            serverAddress = "192.0.2.1"
            serverPort = 1080
        }
        return ProxyEntity(groupId = groupId).apply {
            putBean(bean)
            id = env.database.proxyDao().addProxy(this)
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

    @Test fun selectorCompilesOnlyTheActiveGroupsMembers() {
        val a = node("A", "a-one")
        val c = addToGroup(a.groupId, "a-three")
        val result = buildConfig(a)
        val root = dnsJson(result)
        val selector = root.getAsJsonArray("outbounds").single {
            it.asJsonObject["tag"].asString == "proxy"
        }.asJsonObject

        assertEquals(a.groupId, result.selectorGroupId)
        assertEquals(setOf(a.id, c.id), result.profileTagMap.keys)
        assertEquals(result.profileTagMap.values.toSet(),
            selector.getAsJsonArray("outbounds").map { it.asString }.toSet())
    }

    @Test fun nonSelectorGroupDoesNotCompileASelector() {
        val a = node("A", "a-one")
        val group = env.database.groupDao().getById(a.groupId)!!
        group.isSelector = false
        env.database.groupDao().updateGroup(group)

        val result = buildConfig(a)
        assertEquals(-1L, result.selectorGroupId)
        assertEquals(setOf(a.id), result.profileTagMap.keys)
        assertFalse(dnsJson(result).getAsJsonArray("outbounds").any {
            it.asJsonObject["tag"]?.asString == "proxy" &&
                it.asJsonObject["type"]?.asString == "selector"
        })
    }

    @Test fun selectorMetadataIsStableWhenBuiltFromAnotherMember() {
        val a = node("A", "one")
        val c = addToGroup(a.groupId, "three")
        val fromA = buildConfig(a)
        val fromC = buildConfig(c)

        assertEquals(fromA.profileTagMap, fromC.profileTagMap)
        assertEquals(fromA.selectorGroupId, fromC.selectorGroupId)
        assertEquals(a.groupId, fromA.selectorGroupId)
    }

    @Test fun forTestAndExportUseTheExistingBuildResultMetadata() {
        val a = node("A", "one")
        addToGroup(a.groupId, "three")

        val testResult = buildConfig(a, forTest = true)
        assertEquals(-1L, testResult.selectorGroupId)
        assertEquals(setOf(a.id), testResult.profileTagMap.keys)
        assertFalse(dnsJson(testResult).getAsJsonArray("outbounds").any {
            it.asJsonObject["type"]?.asString == "selector"
        })

        val exportResult = buildConfig(a, forExport = true)
        assertEquals(-1L, exportResult.selectorGroupId)
        assertEquals(setOf(a.id), exportResult.profileTagMap.keys)
    }

    /** Export an exact App JSON document when -Dneko.dns.fixtureDir is set. */
    private fun exportDnsFixture(name: String, result: ConfigBuildResult) {
        val directory = System.getProperty("neko.dns.fixtureDir")?.takeIf { it.isNotBlank() } ?: return
        val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        java.io.File(directory).apply { mkdirs() }
            .resolve("$safeName.json").writeText(result.config)
    }

    private fun dnsJson(result: ConfigBuildResult) =
        com.google.gson.JsonParser.parseString(result.config).asJsonObject

    private fun assertDns14ServerShape(root: com.google.gson.JsonObject) {
        val dns = root.getAsJsonObject("dns")
        assertFalse("DNSOptions.independent_cache must be absent", dns.has("independent_cache"))
        assertFalse("legacy top-level dns.fakeip must be absent", dns.has("fakeip"))
        dns.getAsJsonArray("servers").forEach { element ->
            val server = element.asJsonObject
            assertTrue("DNS transport type is required: $server",
                server["type"]?.asString?.isNotBlank() == true)
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
        assertEquals(-1L, result.selectorGroupId)
        assertEquals(setOf(a.id), result.profileTagMap.keys)
        assertDns14ServerShape(root)
        val direct = dns.getAsJsonArray("servers").single {
            it.asJsonObject["tag"].asString == "dns-direct"
        }.asJsonObject
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
        val fake = dns.getAsJsonArray("servers").single {
            it.asJsonObject["tag"]?.asString == "dns-fake"
        }.asJsonObject
        assertEquals("fakeip", fake["type"].asString)
        assertEquals("198.18.0.0/15", fake["inet4_range"].asString)
        assertEquals("fc00::/18", fake["inet6_range"].asString)
        assertTrue(dns.getAsJsonArray("rules").filter {
            it.asJsonObject["server"]?.asString == "dns-fake"
        }.all { !it.asJsonObject.has("strategy") })
        assertTrue("route.default_domain_resolver is required", route.has("default_domain_resolver"))
        assertEquals("dns-direct", route["default_domain_resolver"].asJsonObject["server"].asString)
        assertTrue(route.getAsJsonObject("default_domain_resolver")["disable_cache"].asBoolean)
        val remote = dns.getAsJsonArray("servers").single {
            it.asJsonObject["tag"].asString == "dns-remote"
        }.asJsonObject
        assertEquals("proxy", remote["detour"].asString)
        assertDns14ServerShape(root)
        exportDnsFixture("runtime-fakeip", result)
    }

    @Test fun subscriptionSelectorRetainsScopedPolicyAndCustomTcpTlsServers() {
        val a = node("DNS selector", "selector")
        dns(a,
            server = "tcp://resolver.example:8080\ntls://192.0.2.53:853",
            direct = "https://1.1.1.1/dns-query",
            hosts = "hosts:\n  node.example: 192.0.2.77\n")
        DataStore.enableFakeDns = true
        val result = buildConfig(a)
        val root = dnsJson(result)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
            .map { it.asJsonObject }
        val types = servers.mapNotNull { it["type"]?.asString }

        assertEquals(a.groupId, result.selectorGroupId)
        assertTrue("subscription policy transport missing", types.contains("neko-policy"))
        assertTrue("custom TCP DNS transport was not retained", types.contains("neko-tcp"))
        assertTrue("custom TLS DNS transport was not retained", types.contains("neko-tls"))
        assertTrue("subscription hosts must be retained", servers.any {
            it["type"]?.asString == "neko-policy" && it.toString().contains("node.example")
        })
        val tcp = servers.single { it["type"]?.asString == "neko-tcp" }
        assertEquals("resolver.example", tcp["server"].asString)
        assertEquals(8080, tcp["server_port"].asInt)
        assertEquals("dns-local", tcp["domain_resolver"].asString)
        assertDns14ServerShape(root)
        exportDnsFixture("subscription-selector-tcp-tls", result)
    }

    @Test fun blockedDnsRuleUsesPredefinedNoErrorWithoutAResolverServer() {
        val a = node("DNS block", "blocked")
        val group = env.database.groupDao().getById(a.groupId)!!
        group.isSelector = false
        env.database.groupDao().updateGroup(group)
        DataStore.enableFakeDns = false
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
        assertEquals(-1L, result.selectorGroupId)
        assertDns14ServerShape(root)
        exportDnsFixture("block-predefined", result)
    }

    @Test fun localDirectAndRemoteDoNotLeakNetworkOnlyOptions() {
        val a = node("local", "local-dns")
        val group = env.database.groupDao().getById(a.groupId)!!
        group.isSelector = false
        env.database.groupDao().updateGroup(group)
        DataStore.directDns = "local"
        DataStore.remoteDns = "local"
        DataStore.enableFakeDns = false
        val result = buildConfig(a)
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
        assertEquals(-1L, result.selectorGroupId)
        assertDns14ServerShape(root)
        exportDnsFixture("runtime-local", result)
    }

    @Test fun nonSelectorSubscriptionOverrideAndHostsUseModernDns() {
        val a = node("subscription", "single-node")
        val group = env.database.groupDao().getById(a.groupId)!!
        group.isSelector = false
        env.database.groupDao().updateGroup(group)
        dns(a, server = "tcp://resolver.example:5353", direct = "tls://192.0.2.53:8853")
        DataStore.dnsHosts = "app.example 203.0.113.8"
        DataStore.enableFakeDns = false
        val result = buildConfig(a)
        val root = dnsJson(result)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }
        assertEquals("neko-policy", servers.single {
            it["tag"]?.asString == "dns-direct"
        }["type"].asString)
        assertTrue(servers.any {
            it["type"]?.asString == "neko-tls" && it["server_port"]?.asInt == 8853
        })
        val hosts = servers.single { it["tag"].asString == "dns-hosts" }
        assertEquals("hosts", hosts["type"].asString)
        assertEquals("203.0.113.8",
            hosts.getAsJsonObject("predefined").getAsJsonArray("app.example").single().asString)
        val hostsRule = root.getAsJsonObject("dns").getAsJsonArray("rules").single {
            it.asJsonObject["server"]?.asString == "dns-hosts"
        }.asJsonObject
        assertEquals("app.example", hostsRule.getAsJsonArray("domain").single().asString)
        assertFalse(hostsRule.has("ip_accept_any"))
        assertEquals(-1L, result.selectorGroupId)
        assertDns14ServerShape(root)
        exportDnsFixture("subscription-single-hosts", result)
    }
}
