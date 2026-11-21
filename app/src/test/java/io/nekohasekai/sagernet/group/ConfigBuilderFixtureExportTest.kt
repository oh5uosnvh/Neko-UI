package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.TempDatabase
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.security.MessageDigest

/** Export the unmodified FINAL App JSON, not a hand-written config or a DNS projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ConfigBuilderFixtureExportTest {
    private data class Scenario(
        val name: String, val native: Boolean = false,
        val ipv6: Int = IPv6Mode.DISABLE, val sniff: Int = 0,
        val resolve: Boolean = false, val fakeip: Boolean = false,
        val stack: Int = TunImplementation.SYSTEM,
        val local: Boolean = false, val subscription: Boolean = false,
        val selector: Boolean = false, val custom: Boolean = false,
        val domainDns: Boolean = false,
    )

    private val scenarios = listOf(
        Scenario("hev-ipv4-sniff-off"),
        Scenario("hev-dual-sniff-on", ipv6 = IPv6Mode.ENABLE, sniff = 1),
        Scenario("hev-ipv6-sniff-override-resolve", ipv6 = IPv6Mode.ONLY, sniff = 2, resolve = true),
        Scenario("native-ipv4-system-sniff-off", native = true),
        Scenario("native-dual-mixed-sniff-on-resolve", native = true, ipv6 = IPv6Mode.ENABLE,
            sniff = 1, resolve = true, stack = TunImplementation.MIXED),
        Scenario("native-ipv6-gvisor-sniff-override", native = true, ipv6 = IPv6Mode.ONLY,
            sniff = 2, stack = TunImplementation.GVISOR),
        Scenario("hev-fakeip", sniff = 1, fakeip = true),
        Scenario("hev-local-dns-hosts", local = true),
        Scenario("hev-domain-bootstrap-dns", domainDns = true, resolve = true),
        Scenario("hev-subscription-single", subscription = true),
        Scenario("hev-subscription-selector", subscription = true, selector = true),
        Scenario("hev-custom-merge", custom = true),
    )

    /** Same singleton replacement habit as ChainEditorHeaderTest, but ALL DAOs are real Room. */
    private class Environment : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        private val preferences = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java)
            .allowMainThreadQueries().build()
        private val cache = Room.inMemoryDatabaseBuilder(context, TempDatabase::class.java)
            .allowMainThreadQueries().build()
        private val restore = ArrayList<() -> Unit>()

        init {
            val appField = SagerNet::class.java.getDeclaredField("application").apply { isAccessible = true }
            val oldApp = appField.get(null)
            // Do NOT call SagerNet.onCreate / Libcore / go.Seq in the JVM exporter.
            ReflectionHelpers.callInstanceMethod<Void>(SagerNet(), "attachBaseContext",
                ClassParameter.from(Context::class.java, context))
            restore += { appField.set(null, oldApp) }
            for ((type, value) in listOf(SagerDatabase::class.java to database,
                PublicDatabase::class.java to preferences, TempDatabase::class.java to cache)) {
                val lazy = ReflectionHelpers.getStaticField<Any>(type, "instance\$delegate")
                val old = ReflectionHelpers.getField<Any>(lazy, "_value")
                val initializer = ReflectionHelpers.getField<Any?>(lazy, "initializer")
                ReflectionHelpers.setField(lazy, "_value", value)
                ReflectionHelpers.setField(lazy, "initializer", null)
                restore += {
                    ReflectionHelpers.setField(lazy, "_value", old)
                    ReflectionHelpers.setField(lazy, "initializer", initializer)
                }
            }
            for ((store, dao) in listOf(DataStore.configurationStore to preferences.keyValuePairDao(),
                DataStore.profileCacheStore to cache.profileCacheDao())) {
                val old = ReflectionHelpers.getField<Any>(store, "kvPairDao")
                ReflectionHelpers.setField(store, "kvPairDao", dao)
                restore += { ReflectionHelpers.setField(store, "kvPairDao", old) }
            }
        }

        fun reset() {
            database.clearAllTables()
            DataStore.configurationStore.reset()
            DataStore.profileCacheStore.reset()
        }

        override fun close() {
            restore.asReversed().forEach { it() }
            cache.close(); preferences.close(); database.close()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sourceHashes(repo: File): Map<String, String> = listOf(
        "app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt",
        "app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt",
        "app/src/main/java/io/nekohasekai/sagernet/database/DefaultRouteCatalog.kt",
        "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionDnsBuilder.kt",
        "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionDns.java",
        "app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java",
        "app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt",
    ).associateWith { path ->
        val file = File(repo, path)
        check(file.isFile) { "Missing production source: $file" }
        sha256(file.readBytes())
    }

    private fun configure(s: Scenario) {
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.enableHevTun = !s.native
        DataStore.ipv6Mode = s.ipv6
        DataStore.tunImplementation = s.stack
        DataStore.trafficSniffing = s.sniff
        DataStore.resolveDestination = s.resolve
        DataStore.enableFakeDns = s.fakeip
        DataStore.enableDnsRouting = true
        DataStore.strictRoute = true
        DataStore.mtu = 1500
        DataStore.allowAccess = false
        DataStore.mixedPort = 32080 // All boxes start serially; the fixture JSON is never patched for ports.
        DataStore.mixedUsername = "fixture"
        DataStore.configurationStore.putString(Key.MIXED_SECRET, "fixture-only-not-a-user-secret")
        DataStore.enableClashAPI = false
        DataStore.enableTLSFragment = false
        DataStore.globalMode = false
        DataStore.bypassLan = false
        DataStore.bypassLanInCore = true
        DataStore.concurrentDial = false
        DataStore.rulesProvider = 0
        DataStore.logLevel = 1
        DataStore.directDns = when { s.local -> "local"; s.domainDns -> "https://bootstrap.fixture.example:8443/bootstrap-query"; else -> "udp://127.0.0.1:15353" }
        DataStore.remoteDns = when { s.local -> "local"; s.domainDns -> "tls://remote.fixture.example:1853"; else -> "https://127.0.0.1:15443/remote-query" }
        DataStore.dnsHosts = if (s.local) "hosts.fixture.example 192.0.2.10 2001:db8::10" else ""
        DataStore.globalCustomConfig = if (s.custom) """
            {"dns":{"reverse_mapping":true},"experimental":{"cache_file":{"cache_id":"global-fixture"}},
             "route":{"rule_set+":[{"type":"local","tag":"fixture-source","format":"source","path":"rules/fixture-source.json"}],
                      "rules+":[{"rule_set":["fixture-source"],"action":"route","outbound":"bypass"}]}}
        """.trimIndent() else ""
    }

    private fun seed(environment: Environment, s: Scenario): ProxyEntity {
        // Seed the real default catalog, including independent CN domain AND IP rules.
        DefaultRouteCatalog.items.forEachIndexed { index, item ->
            environment.database.rulesDao().createRule(DefaultRouteCatalog.defaults(index, item))
        }
        val subscription = if (s.subscription) SubscriptionBean().apply {
            initializeDefaultValues()
            applyHosts = false
            directDnsResolver = "udp://127.0.0.1:15353\ntls://127.0.0.1:1853"
            serverDnsResolver = "tcp://resolver.fixture.example:15353\ntls://127.0.0.1:1853"
            importedDnsConfig = """
                dns:
                  default-nameserver: [udp://127.0.0.1:15353]
                  nameserver-policy:
                    '+.private.fixture.example': [tcp://resolver.fixture.example:15353, tls://127.0.0.1:1853]
                  proxy-server-nameserver-policy:
                    '+.node.fixture.example': [tcp://resolver.fixture.example:15353]
                hosts:
                  hosts.fixture.example: [192.0.2.10, '2001:db8::10']
            """.trimIndent()
        } else null
        val group = ProxyGroup(name = "Fixture ${s.name}",
            type = if (s.subscription) GroupType.SUBSCRIPTION else GroupType.BASIC,
            subscription = subscription, isSelector = s.selector)
        group.id = environment.database.groupDao().createGroup(group)
        fun node(name: String): ProxyEntity = ProxyEntity(groupId = group.id).putBean(SOCKSBean().apply {
            initializeDefaultValues()
            this.name = name
            serverAddress = if (s.subscription || s.domainDns) "node.fixture.example" else "127.0.0.1"
            serverPort = 19080
            if (s.custom) {
                customOutboundJson = "{\"connect_timeout\":\"2s\"}"
                customConfigJson = """
                    {"dns":{"cache_capacity":4096},"experimental":{"cache_file":{"cache_id":"profile-fixture"}},
                     "route":{"rule_set+":[{"type":"local","tag":"fixture-binary","format":"binary","path":"rules/fixture-binary.srs"}],
                              "rules+":[{"rule_set":["fixture-binary"],"action":"route","outbound":"bypass"}]}}
                """.trimIndent()
            }
        }).also { it.id = environment.database.proxyDao().addProxy(it) }
        val selected = node("Fixture node A")
        if (s.selector) node("Fixture node B") // Must use the SAME real groupId.
        DataStore.selectedGroup = group.id
        DataStore.selectedProxy = selected.id
        return environment.database.proxyDao().getById(selected.id)!!
    }

    private fun objects(root: JsonObject, key: String): List<JsonObject> =
        root.getAsJsonArray(key)?.map { it.asJsonObject } ?: emptyList()

    private fun assertCoverage(root: JsonObject, s: Scenario, forTest: Boolean) {
        assertTrue(root.has("log")); assertTrue(root.has("dns")); assertTrue(root.has("route"))
        assertTrue("Final outbounds must not be projected away", objects(root, "outbounds").isNotEmpty())
        val inbounds = objects(root, "inbounds")
        assertEquals(!forTest, inbounds.any { it["type"].asString == "mixed" })
        assertEquals(s.native && !forTest, inbounds.any { it["type"].asString == "tun" })
        val dns = root.getAsJsonObject("dns")
        val servers = objects(dns, "servers")
        assertTrue(servers.any { it["tag"].asString == "dns-local" })
        assertTrue(servers.any { it["tag"].asString == "dns-direct" })
        assertEquals(!forTest, servers.any { it["tag"].asString == "dns-remote" })
        assertEquals(if (forTest) "dns-direct" else "dns-remote", dns["final"].asString)
        val fake = servers.any { it["type"]?.asString == "fakeip" || it["address"]?.asString == "fakeip" }
        assertEquals(s.fakeip && !forTest, fake)
        val route = root.getAsJsonObject("route")
        if (!forTest) {
            assertTrue("Do not discard experimental/cache_file", root.has("experimental"))
            val sets = objects(route, "rule_set")
            for (tag in listOf("geosite:cn", "geoip:cn", "geosite:category-ads-all")) {
                assertTrue("Missing default resource $tag", sets.any { it["tag"].asString == tag && it["path"].asString == tag })
                assertTrue("Resource $tag must be referenced by a routing rule", objects(route, "rules").any { rule ->
                    rule.getAsJsonArray("rule_set")?.any { it.asString == tag } == true
                })
            }
        }
        if (s.local) assertTrue(servers.any { it["type"]?.asString == "hosts" })
        if (s.subscription) {
            for (type in listOf("neko-policy", "neko-tcp", "neko-tls")) {
                assertTrue("Missing subscription production transport $type", servers.any { it["type"]?.asString == type })
            }
        }
        val selector = objects(root, "outbounds").firstOrNull { it["type"].asString == "selector" }
        assertEquals(s.selector && !forTest, selector != null)
        if (selector != null) assertEquals("Two same-group nodes are required", 2, selector.getAsJsonArray("outbounds").size())
        if (s.custom) {
            assertEquals(4096, dns["cache_capacity"].asInt)
            val sets = objects(route, "rule_set").map { it["tag"].asString }
            assertTrue("Profile custom config also applies to URLTest", "fixture-binary" in sets)
            assertEquals(!forTest, "fixture-source" in sets)
            assertEquals(!forTest, dns.has("reverse_mapping"))
            assertEquals("profile-fixture", root.getAsJsonObject("experimental")
                .getAsJsonObject("cache_file")["cache_id"].asString)
            assertTrue(objects(root, "outbounds").any { it["connect_timeout"]?.asString == "2s" })
        }
        // sniff/resolve/IPv6 fields are deliberately NOT repaired here: the target decoder
        // consumes the original JSON and must fail if production schema migration is incomplete.
    }

    @Test fun exportRealFinalConfigsForProductionAndUrlTest() {
        val property = System.getProperty("neko.config.fixtureDir")
        check(!property.isNullOrBlank()) { "neko.config.fixtureDir must be forwarded to the Test JVM" }
        val output = File(property!!).absoluteFile
        check(output.isDirectory || output.mkdirs()) { "Cannot create fixture directory $output" }
        val repo = File(checkNotNull(System.getProperty("neko.config.repoDir")) {
            "neko.config.repoDir must be forwarded to the Test JVM"
        })
        val hashes = sourceHashes(repo)
        val expectedNames = scenarios.flatMap { s -> listOf("${s.name}-production.json", "${s.name}-urltest.json") }
        // Remove previous completion marker AND previous expected JSON, never accept stale exports.
        (expectedNames + "manifest.json").forEach { name ->
            val file = File(output, name)
            check(!file.exists() || file.delete()) { "Cannot remove stale fixture $file" }
        }
        val entries = ArrayList<Map<String, Any>>()
        Environment().use { environment ->
            for (s in scenarios) {
                environment.reset()
                configure(s)
                val profile = seed(environment, s)
                for (forTest in listOf(false, true)) {
                    // Both real public entry modes, with the default forExport=false.
                    val result = buildConfig(profile, forTest)
                    val bytes = result.config.toByteArray(Charsets.UTF_8)
                    check(bytes.isNotEmpty()) { "Empty ConfigBuilder output for ${s.name}" }
                    val name = "${s.name}-${if (forTest) "urltest" else "production"}.json"
                    File(output, name).writeBytes(bytes) // Byte-for-byte production result, NO parse/re-serialization.
                    val root = JsonParser.parseString(result.config).asJsonObject
                    assertCoverage(root, s, forTest)
                    entries += linkedMapOf(
                        "name" to name, "sha256" to sha256(bytes), "bytes" to bytes.size,
                        "scenario" to s.name, "for_test" to forTest,
                        "host_lifecycle" to !(s.native && !forTest),
                        "device_tun_required" to (s.native && !forTest),
                        "sniff_mode" to s.sniff, "resolve_destination" to s.resolve,
                        "ipv6_mode" to s.ipv6,
                    )
                }
            }
        }
        assertEquals("Concurrent source changes must invalidate export", hashes, sourceHashes(repo))
        assertEquals(expectedNames.toSet(), entries.map { it["name"] }.toSet())
        expectedNames.forEach { name -> assertTrue("Missing/empty required fixture $name", File(output, name).length() > 0L) }
        val manifest = linkedMapOf(
            "schema_version" to 1, "target_sing_box" to "v1.15.0-alpha.13",
            "producer" to "ConfigBuilderFixtureExportTest/buildConfig(profile,false|true)",
            "source_sha256" to hashes, "fixtures" to entries,
        )
        File(output, "manifest.json").writeText(gson.toJson(manifest), Charsets.UTF_8) // Written LAST, only after all assertions.
    }
}
