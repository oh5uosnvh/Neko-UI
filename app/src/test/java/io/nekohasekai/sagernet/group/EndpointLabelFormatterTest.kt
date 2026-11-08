package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.widget.EndpointLabelFormatter
import org.junit.Assert.*
import org.junit.Test

/** Deterministic geometry tests, independent of Robolectric's legacy fake fonts. */
class EndpointLabelFormatterTest {
    private fun fit(value: String, width: Int): String = EndpointLabelFormatter.fit(
        value, width.toFloat()
    ) { it.codePointCount(0, it.length).toFloat() }

    @Test fun fullAddressOrHostEllipsisThenCompletePort() {
        val source = "bgp01.dn-sefan04851.com:443"
        assertEquals(source, fit(source, 100))
        assertEquals("bgp01.dn-sefan0…:443", fit(source, 20))
        assertEquals("…:443", fit(source, 5))
        assertEquals(":443", fit(source, 4))
        assertEquals(":443", fit(source, 1)) // renderer scales this exceptional fallback
        assertEquals("", fit(source, 0))
    }

    @Test fun everySupportedPortSpecificationIsAtomic() {
        for (ports in listOf("1", "443", "8443", "65535", "443,8443", "1000-2000",
            "1000:2000", "443,1000-2000,3000:4000")) {
            val suffix = ":$ports"
            val result = fit("very-long-host.example.test$suffix", suffix.length + 4)
            assertTrue("result=$result", result.endsWith(suffix))
            assertTrue("result=$result", result.contains("…"))
            assertTrue(result.length <= suffix.length + 4)
        }
    }

    @Test fun truncatedIpv6RetainsBracketsAndPortRange() {
        assertEquals("[2001:…]:8443", fit("[2001:db8:ffff::1234]:8443", 13))
        val result = fit("[fe80::1234%wlan0]:1000:2000", 18)
        assertTrue(result.startsWith("["))
        assertTrue(result.endsWith("]:1000:2000"))
    }

    @Test fun hostsAreOnlyShortenedNotNormalizedOrMutated() {
        val host = "bgp01.dn-sefan04851.com"
        val full = "$host:443"
        assertTrue(fit(full, 20).startsWith("bgp01.dn-sefan"))
        assertEquals("$host:443", full)
        assertEquals("without-…", fit("without-port.example", 9))
    }

    @Test fun supplementaryCodePointsAreNotSplit() {
        val result = fit("abcd😀efghi.example:443", 10)
        assertEquals("abcd😀…:443", result)
        assertFalse(result.contains('\uFFFD'))
        assertEquals(10, result.codePointCount(0, result.length))
    }

    @Test fun usesRealVariableGlyphWidthsNotCharacterLimit() {
        val measure: (String) -> Float = { s -> s.fold(0f) { total, ch -> total + if (ch == 'W') 10f else 1f } }
        val result = EndpointLabelFormatter.fit("WWWW.example:443", 25f, measure)
        assertTrue(result.endsWith(":443"))
        assertTrue(measure(result) <= 25f)
        assertTrue(result.length < 25)
    }
}
