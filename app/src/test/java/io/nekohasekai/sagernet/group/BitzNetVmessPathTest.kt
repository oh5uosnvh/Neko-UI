package io.nekohasekai.sagernet.group

import android.app.Application
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_WebsocketOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.Yaml
import java.io.File

/** Provider-shaped fixtures contain no subscription credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BitzNetVmessPathTest {
    private fun yaml(extra: String): String = """
        proxies:
          - name: path-test
            type: vmess
            server: node.example
            port: 443
            uuid: 11111111-1111-4111-8111-111111111111
            alterId: 0
            cipher: auto
            network: ws
    """.trimIndent() + "\n" + extra.prependIndent("    ") + "\n"

    private suspend fun node(extra: String): VMessBean =
        RawUpdater.parseRaw(yaml(extra))!!.single() as VMessBean

    @Test fun providerMixedCasePathSurvivesImportAndOutboundBuild() = runBlocking {
        val bean = node("""
            ws-opts:
              Path: /provider/socket?token=synthetic%2Fvalue
              headers:
                Host: edge.example
            ws-path: /legacy
        """.trimIndent())
        assertEquals("ws", bean.type)
        assertEquals("/provider/socket?token=synthetic%2Fvalue", bean.path)
        assertEquals("edge.example", bean.host)
        assertEquals(bean.path, (buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_WebsocketOptions).path)
    }

    @Test fun legacyWsPathIsRecognizedWithoutModernBlock() = runBlocking {
        val bean = node("""
            ws-path: /legacy/socket
            ws-headers: {hOsT: legacy.example}
        """.trimIndent())
        assertEquals("/legacy/socket", bean.path)
        assertEquals("legacy.example", bean.host)
    }

    @Test fun modernPathHasDeterministicPrecedenceRegardlessOfYamlOrder() = runBlocking {
        for (extra in listOf(
            "ws-path: /legacy\nws-opts: {Path: /mixed, path: /modern}",
            "ws-opts: {path: /modern, Path: /mixed}\nws-path: /legacy",
        )) assertEquals("/modern", node(extra).path)
        assertEquals("", node("ws-path: /legacy\nws-opts: {path: ''}").path)
    }

    @Test fun inactiveTransportOptionsCannotOverwriteWebSocketPathOrHost() = runBlocking {
        val bean = node("""
            ws-opts: {Path: /selected, headers: {Host: selected.example}}
            h2-opts: {path: /wrong, host: [wrong.example]}
            http-opts: {path: [/wrong], headers: {Host: [wrong.example]}}
            grpc-opts: {grpc-service-name: wrong}
        """.trimIndent())
        assertEquals("/selected", bean.path)
        assertEquals("selected.example", bean.host)
    }

    @Test fun httpUpgradeAndEarlyDataAreAppliedAfterNetworkSelection() = runBlocking {
        val bean = node("""
            ws-opts:
              Path: /upgrade
              max-early-data: 2048
              early-data-header-name: Sec-WebSocket-Protocol
              v2ray-http-upgrade: true
        """.trimIndent())
        assertEquals("httpupgrade", bean.type)
        assertEquals("/upgrade", bean.path)
        assertEquals(2048, bean.wsMaxEarlyData.toInt())
        assertEquals("Sec-WebSocket-Protocol", bean.earlyDataHeaderName)
    }

    @Test fun newerSavedWsBeanDoesNotShiftFollowingTlsFields() = runBlocking {
        val bean = node("ws-path: /persisted").apply {
            security = "tls"
            sni = "tls.example"
        }
        val bytes = KryoConverters.serialize(bean)
        val prefix = ByteBufferInput(bytes)
        assertEquals(10, prefix.readInt())
        prefix.readString(); prefix.readInt() // AbstractBean server/port
        prefix.readString(); prefix.readString(); prefix.readString() // UUID/encryption/VLESS encryption
        prefix.readInt() // VMess alterId
        assertEquals("ws", prefix.readString())
        prefix.readString(); prefix.readString(); prefix.readInt(); prefix.readString()
        val insertion = prefix.position()
        val extra = ByteBufferOutput(128, -1).apply { writeString("{\"X-Example\":\"synthetic\"}") }.toBytes()
        val version = ByteBufferOutput(4, 4).apply { writeInt(11) }.toBytes()
        val newerBytes = bytes.copyOfRange(0, insertion) + extra + bytes.copyOfRange(insertion, bytes.size)
        version.copyInto(newerBytes, 0)
        val restored = KryoConverters.deserialize(VMessBean(), newerBytes)
        assertEquals("/persisted", restored.path)
        assertEquals("tls", restored.security)
        assertEquals("tls.example", restored.sni)
        assertEquals(bean.uuid, restored.uuid)
    }

    @Test fun privateAttachmentAllNineVmessPathsMatchSourceWithoutPrintingSecrets() = runBlocking {
        val requested = System.getProperty("neko.bitz.fixture") ?: System.getenv("NEKO_BITZ_FIXTURE")
        org.junit.Assume.assumeTrue("Private attachment is a local-only test input", requested != null)
        val file = File(requested!!)
        assertTrue("Requested private attachment must exist", file.isFile)
        val text = file.readText()
        val document = Yaml().load<Map<String, Any?>>(text)
        val source = (document["proxies"] as List<Map<String, Any?>>).filter { it["type"] == "vmess" }
        val imported = RawUpdater.parseRaw(text)!!.filterIsInstance<VMessBean>().filter { !it.isVLESS }
        assertEquals(9, source.size)
        assertEquals(9, imported.size)
        source.zip(imported).forEachIndexed { index, (raw, bean) ->
            val opts = raw["ws-opts"] as? Map<*, *>
            val expected = opts?.get("path") ?: opts?.entries?.firstOrNull {
                it.key?.toString()?.equals("path", true) == true
            }?.value ?: raw["ws-path"] ?: raw["path"] ?: ""
            // Do not include actual path, server, UUID or host in test failures/reports.
            assertTrue("private VMess path mismatch at index $index", expected.toString() == bean.path)
            assertTrue("outbound path mismatch at index $index",
                bean.path == (buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_WebsocketOptions).path)
        }
        println("Private Bitz Net attachment: 9 VMess paths and generated WS outbounds verified")
    }
}
