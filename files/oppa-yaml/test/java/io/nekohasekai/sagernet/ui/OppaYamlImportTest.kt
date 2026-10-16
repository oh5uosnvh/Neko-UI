package io.nekohasekai.sagernet.ui

import android.app.Application
import io.nekohasekai.sagernet.fmt.oppa.OppaBean
import io.nekohasekai.sagernet.fmt.oppa.parseClashOppa
import io.nekohasekai.sagernet.fmt.oppa.toUri
import io.nekohasekai.sagernet.fmt.oppa.parseOppaNode
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class OppaYamlImportTest {
    @Test fun complete182NodeYamlImportsThroughSharedRawImporter() = runBlocking {
        val text=javaClass.classLoader!!.getResourceAsStream("oppa-clash-sanitized.yaml")!!.bufferedReader().use { it.readText() }
        val nodes=requireNotNull(RawUpdater.parseRaw(text))
        assertEquals(182,nodes.size)
        assertTrue(nodes.all { it is OppaBean })
        val beans=nodes.map { it as OppaBean }
        assertEquals(137,beans.count { it.allowInsecure })
        assertEquals(45,beans.count { it.sni=="tls.example.invalid" })
        beans.forEachIndexed { i,b ->
            assertEquals("node${i+1}.example.invalid",b.serverAddress)
            assertEquals("TestOnly-Not-A-Real-Credential",b.password)
            assertEquals(8,b.preConnect.toInt())
            assertEquals(b.password,parseOppaNode(b.toUri()).password)
        }
    }
    @Test fun preservesPasswordPortAndTlsParametersWithoutTrimming() {
        val b=parseClashOppa(mapOf("server" to "[2001:db8::1]","port" to 1080L,"password" to " a+b:#汉字 ","preconnect" to 16L,"sni" to "example.invalid","skip-cert-verify" to true,"pin-sha256" to "test-pin"))
        assertEquals(1080,b.serverPort.toInt())
        assertEquals(" a+b:#汉字 ",b.password)
        assertEquals(16,b.preConnect.toInt())
        assertEquals("test-pin",b.certificatePinSHA256)
        assertTrue(b.allowInsecure)
    }
    @Test fun rejectsInvalidCredentialsAndPorts() {
        for (p in listOf(mapOf("server" to "x","password" to ""),mapOf("server" to "x","password" to "ok","port" to 70000))) {
            try { parseClashOppa(p);fail("invalid node accepted") } catch (_:IllegalArgumentException) { }
        }
    }
}
