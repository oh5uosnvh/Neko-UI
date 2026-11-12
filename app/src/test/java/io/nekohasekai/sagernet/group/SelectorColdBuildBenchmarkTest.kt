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

/** Synthetic only: never opens device DB, never uses network or ConfigBuildCache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class SelectorColdBuildBenchmarkTest {
    private lateinit var env: QuickPopupTestEnvironment
    @Before fun setup() {
        env = QuickPopupTestEnvironment()
        DataStore.globalCustomConfig = ""
        DataStore.remoteDns = "https://1.1.1.1/dns-query"
        DataStore.directDns = "https://8.8.8.8/dns-query"
    }
    @After fun cleanup() { env.close() }

    @Test fun cold100() = measure(100)
    @Test fun cold1000() = measure(1000)
    @Test fun cold10000() = measure(10000)

    private fun measure(count: Int) {
        val settings = SubscriptionBean().apply {
            initializeDefaultValues()
            importedDnsConfig = "hosts:\n  node.synthetic.test: 192.0.2.1\ndns:\n  nameserver-policy:\n    '+.synthetic.test': [udp://9.9.9.9]\n"
            importedDnsSource = "subscription"
            serverDnsResolver = "udp://1.1.1.1"
        }
        val group = ProxyGroup(name = "synthetic", isSelector = true, subscription = settings)
        group.id = env.database.groupDao().createGroup(group)
        var first: ProxyEntity? = null
        env.database.runInTransaction {
            repeat(count) { index ->
                val bean = SOCKSBean().apply {
                    initializeDefaultValues(); name = "synthetic-$index"
                    serverAddress = "node.synthetic.test"; serverPort = 1080
                }
                val node = ProxyEntity(groupId = group.id).apply { putBean(bean) }
                node.id = env.database.proxyDao().addProxy(node)
                if (first == null) first = node
            }
        }
        val start = System.nanoTime()
        val result = buildConfig(first!!)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(count, result.profileTagMap.size)
        assertTrue(result.config.contains("\"interrupt_exist_connections\":true"))
        // The same normalized group policy must compile once, not once per node.
        val json = com.google.gson.JsonParser.parseString(result.config).asJsonObject
        val servers = json.getAsJsonObject("dns").getAsJsonArray("servers")
        assertTrue("Unexpected per-node DNS expansion: ${servers.size()}", servers.size() < 30)
        println("SELECTOR_COLD_BENCH nodes=$count build_ms=$elapsed json_bytes=${result.config.length} dns_servers=${servers.size()}")
    }
}
