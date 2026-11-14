package io.nekohasekai.sagernet.group

import android.app.Application
import com.google.gson.Gson
import java.io.File
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.SingBoxOptions.Outbound_VMessOptions
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_WebsocketOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class Pre22VmessCompatibilityTest {
    private fun bean() = VMessBean().apply {
        initializeDefaultValues()
        serverAddress = "node.example"
        serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        type = "ws"
        security = "tls"
        sni = "tls.example"
        // Avoid the unrelated global DataStore in this focused cipher test.
        // TLS's boolean OR short-circuits; production defaults are unchanged.
        allowInsecure = true
    }
    private fun outbound(bean: VMessBean) =
        buildSingBoxOutboundStandardV2RayBean(bean) as Outbound_VMessOptions
    private fun ws(bean: VMessBean) =
        buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_WebsocketOptions
    private fun parse(options: String): VMessBean = runBlocking {
        RawUpdater.parseRaw("""
            proxies:
              - name: generated
                type: vmess
                server: node.example
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                alterId: 0
                cipher: auto
                tls: true
                network: ws
        """.trimIndent() + "\n" + options.prependIndent("    "))!!.single() as VMessBean
    }

    @Test fun autoTlsUsesRealAeadCipher() {
        for (cipher in listOf("", "auto", "AUTO")) {
            assertEquals("aes-128-gcm", outbound(bean().apply { encryption = cipher }).security)
        }
    }
    @Test fun explicitCipherIsNotRewritten() {
        for (cipher in listOf("none", "zero", "chacha20-poly1305", "aes-128-gcm")) {
            assertEquals(cipher, outbound(bean().apply { encryption = cipher }).security)
        }
    }
    @Test fun autoWithoutTlsRemainsAuto() {
        assertEquals("auto", outbound(bean().apply { security = "none"; encryption = "auto" }).security)
    }
    @Test fun capitalizedModernWsOptionsAndCustomHeadersReachOutbound() {
        val b = parse("""
            ws-opts:
              Path: /modern
              Headers:
                hOsT: gateway.example
                User-Agent: SyntheticTest/1
                X-Test: generated-value
        """.trimIndent())
        val transport = ws(b)
        assertEquals("/modern", transport.path)
        assertEquals("gateway.example", transport.headers["Host"])
        assertEquals("SyntheticTest/1", transport.headers["User-Agent"])
        assertEquals("generated-value", transport.headers["X-Test"])
    }
    @Test fun legacyWsPathAndHeadersRemainSupported() {
        val transport = ws(parse("""
            ws-path: /legacy
            ws-headers: {Host: legacy.example, X-Legacy: yes-value}
        """.trimIndent()))
        assertEquals("/legacy", transport.path)
        assertEquals("legacy.example", transport.headers["Host"])
        assertEquals("yes-value", transport.headers["X-Legacy"])
    }
    @Test fun caseInsensitiveDuplicateHeadersMergeWithoutLosingOtherFields() {
        val b = parse("""
            ws-headers: {Host: old.example, X-Test: old-value, X-Keep: keep-value}
            ws-opts:
              path: /merged
              headers: {Host: new.example, x-test: new-value}
        """.trimIndent())
        val h = ws(b).headers
        assertEquals("new.example", h["Host"])
        assertEquals("new-value", h["x-test"])
        assertFalse(h.containsKey("X-Test"))
        assertEquals("keep-value", h["X-Keep"])
    }
    @Test fun modernWsOptionsWinInEitherYamlOrder() {
        val legacy = """
            path: /generic
            ws-path: /legacy
            ws-headers: {Host: old.example, X-Test: old-value, X-Keep: keep-value}
        """.trimIndent()
        val modern = """
            ws-opts:
              Path: /modern
              Headers: {hOsT: new.example, x-test: new-value}
        """.trimIndent()
        for (options in listOf("$legacy\n$modern", "$modern\n$legacy")) {
            val transport = ws(parse(options))
            assertEquals("/modern", transport.path)
            assertEquals("new.example", transport.headers["Host"])
            assertEquals("new-value", transport.headers["x-test"])
            assertFalse(transport.headers.containsKey("X-Test"))
            assertEquals("keep-value", transport.headers["X-Keep"])
        }
    }
    @Test fun explicitEmptyModernPathIsNotOverwrittenByLegacy() {
        for (options in listOf("ws-opts: {Path: ''}\nws-path: /legacy",
            "ws-path: /legacy\nws-opts: {Path: ''}")) {
            assertEquals("/", ws(parse(options)).path)
        }
    }
    @Test fun unusedTransportBlocksCannotOverwriteWsOrThrowOnForeignHeaderTypes() {
        val foreign = """
            h2-opts: {host: [unused.example], path: /unused-h2}
            http-opts: {path: [/unused-http], headers: {Host: unused.example}}
            grpc-opts: {grpc-service-name: unused-grpc}
        """.trimIndent()
        val selected = "ws-opts: {Path: /selected, headers: {Host: selected.example}}"
        for (options in listOf("$foreign\n$selected", "$selected\n$foreign")) {
            val b = parse(options)
            assertEquals("ws", b.type)
            assertEquals("/selected", ws(b).path)
            assertEquals("selected.example", ws(b).headers["Host"])
        }
    }
    @Test fun httpUpgradeIsIndependentOfNetworkKeyPosition() {
        val raw = """
            proxies:
              - name: generated
                type: vmess
                server: node.example
                port: 80
                uuid: 00000000-0000-4000-8000-000000000001
                alterId: 0
                cipher: auto
        """.trimIndent()
        val network = "network: ws"
        val options = "ws-opts: {path: /upgrade, v2ray-http-upgrade: true}"
        for (tail in listOf("$network\n$options", "$options\n$network")) {
            val b = runBlocking { RawUpdater.parseRaw(raw + "\n" + tail.prependIndent("    "))!! }
                .single() as VMessBean
            assertEquals("httpupgrade", b.type)
            assertEquals("/upgrade", b.path)
        }
    }
    @Test fun optionalPrivateSourceVmessSmoke() {
        val source = System.getenv("NEKO_VM_SOURCE_FILE").orEmpty()
        // CI always exercises the same import/outbound path without private input.
        val sourceText = if (source.isNotBlank()) File(source).readText() else """
            proxies:
              - name: synthetic-modern
                type: vmess
                server: node.example
                port: 80
                uuid: 00000000-0000-4000-8000-000000000001
                alterId: 0
                cipher: auto
                network: ws
                ws-opts: {Path: /modern, headers: {Host: modern.example}}
                ws-path: /legacy
                ws-headers: {Host: legacy.example}
              - name: synthetic-legacy
                type: vmess
                server: node.example
                port: 80
                uuid: 00000000-0000-4000-8000-000000000002
                alterId: 0
                cipher: auto
                network: ws
                ws-path: /legacy-only
                ws-headers: {Host: legacy.example}
        """.trimIndent()
        val document = SubscriptionDns.loadDocument(sourceText)
        val sourceNodes = (document["proxies"] as List<*>).filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "vmess" }
        val parsed = runBlocking { RawUpdater.parseRaw(sourceText)!! }
            .filterIsInstance<VMessBean>().filter { !it.isVLESS }
        assertTrue("Private source must contain VMess", sourceNodes.isNotEmpty())
        assertEquals(sourceNodes.size, parsed.size)
        val outputDir = System.getenv("NEKO_VM_OUTPUT_DIR").orEmpty().takeIf { it.isNotBlank() }
            ?.let { File(it).apply { mkdirs(); setReadable(false, false); setReadable(true, true)
                setWritable(false, false); setWritable(true, true)
                setExecutable(false, false); setExecutable(true, true) } }
        for ((index, pair) in sourceNodes.zip(parsed).withIndex()) {
            val (original, b) = pair
            val opts = original["ws-opts"] as? Map<*, *>
            val modernPath = opts?.entries?.firstOrNull { it.key.toString().equals("path", true) }
            val expectedPath = (modernPath?.value?.toString()
                ?: original["ws-path"]?.toString() ?: original["path"]?.toString()).orEmpty()
            val transport = ws(b)
            // Do not use assertEquals(expected, actual) for private values: JUnit exposes both.
            assertTrue("VMess ${index + 1}: bean path lost", b.path == expectedPath)
            assertTrue("VMess ${index + 1}: outbound path lost",
                transport.path == expectedPath.ifBlank { "/" })
            val headers = opts?.entries?.firstOrNull { it.key.toString().equals("headers", true) }
                ?.value as? Map<*, *> ?: original["ws-headers"] as? Map<*, *>
            val expectedHost = headers?.entries?.firstOrNull { it.key.toString().equals("host", true) }
                ?.value?.toString().orEmpty()
            assertTrue("VMess ${index + 1}: Host changed", b.host == expectedHost)
            val generated = outbound(b)
            assertTrue("VMess ${index + 1}: unexpected TLS", (generated.tls != null) == (original["tls"] == true))
            outputDir?.let { directory ->
                val file = File(directory, "vmess-${index + 1}.json")
                file.createNewFile(); file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
                file.writeText(Gson().toJson(generated))
            }
        }
    }
    @Test fun databaseRoundTripRetainsHeadersTlsAndPath() {
        val original = bean().apply {
            path = "/persisted"
            host = "ws.example"
            wsExtraHeaders = "{\"X-Test\":\"generated-value\"}"
        }
        val copy = KryoConverters.vmessDeserialize(KryoConverters.serialize(original))
        assertEquals(original.wsExtraHeaders, copy.wsExtraHeaders)
        assertEquals(original.sni, copy.sni)
        assertEquals(original.security, copy.security)
        assertEquals(original.path, copy.path)
        assertEquals("generated-value", ws(copy).headers["X-Test"])
    }
    @Test fun earlyDataAndDefaultPathStillWork() {
        assertEquals("/", ws(bean()).path)
        val transport = ws(bean().apply { path = "/ed?ed=2048" })
        assertEquals("/ed", transport.path)
        assertEquals(2048, transport.max_early_data)
        assertEquals("Sec-WebSocket-Protocol", transport.early_data_header_name)
    }
}
