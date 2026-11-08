package io.nekohasekai.sagernet.group

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.File

/** Optional private inputs never enter source or reports; CI uses generated YAML. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubscriptionResponseCompatibilityTest {
    @Test fun anytlsYamlWithSourceDnsSelectorImportsRatherThanInvalidDomain() = runBlocking {
        val privateBody = System.getenv("NEKO_SUBSCRIPTION_BODY1")?.let(::File)?.takeIf { it.isFile }
        val text = privateBody?.readText() ?: """
            dns:
              enable: true
              nameserver-policy:
                'geosite:cn': ['1.1.1.1']
                'domain:resolver.example': ['8.8.8.8']
            proxies:
              - {name: test, type: anytls, server: node.example, port: 443, password: test-secret, sni: tls.example}
        """.trimIndent()
        val nodes = RawUpdater.parseRaw(text)
        assertNotNull(nodes)
        assertEquals(if (privateBody == null) 1 else 37, nodes!!.size)
        assertTrue(nodes.all { it.javaClass.simpleName == "AnyTLSBean" })
        println("SUBSCRIPTION source=${if (privateBody == null) "generated" else "private1"} nodes=${nodes.size} PASS")
    }

    @Test fun mixedClashYamlRemainsCompatible() = runBlocking {
        val file = System.getenv("NEKO_SUBSCRIPTION_BODY2")?.let(::File)?.takeIf { it.isFile }
        if (file == null) return@runBlocking
        val nodes = RawUpdater.parseRaw(file.readText())
        assertEquals(37, nodes!!.size)
        println("SUBSCRIPTION source=private2 nodes=${nodes.size} PASS")
    }
}
