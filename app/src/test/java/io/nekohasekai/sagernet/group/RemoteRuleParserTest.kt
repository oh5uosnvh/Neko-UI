package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.*
import org.junit.Assert.*
import org.junit.Test

class RemoteRuleParserTest {
    @Test fun userMicrosoftUrlFixtureParsesActualPayload() {
        val text = javaClass.getResourceAsStream("/routes/Microsoft.yaml")!!.bufferedReader().use { it.readText() }
        val result = RemoteRuleParser.parse(text)
        assertEquals(668, result.count)
        assertEquals(2, result.skipped) // PROCESS-NAME cannot be represented as a domain/IP provider.
        assertTrue(result.domains.contains("domain:microsoft.com"))
        assertTrue(result.skippedReason.contains("PROCESS-NAME"))
    }
    @Test fun microsoftClassicalPayloadDeduplicatesDomainsAndIpv6() {
        val result = RemoteRuleParser.parse("""
            payload:
              - DOMAIN-SUFFIX,microsoft.com
              - DOMAIN-SUFFIX,microsoft.com
              - DOMAIN,login.live.com
              - DOMAIN-KEYWORD,office
              - IP-CIDR,13.64.0.0/11,no-resolve
              - IP-CIDR6,2603::/16,no-resolve
        """.trimIndent())
        assertEquals(5, result.count)
        assertEquals(listOf("domain:microsoft.com", "full:login.live.com", "keyword:office"), result.domains)
        assertEquals(listOf("13.64.0.0/11", "2603::/16"), result.ips)
    }
    @Test fun domainAndIpcidrProviderPayloadsWork() {
        assertEquals(listOf("domain:example.com", "full:exact.test"),
            RemoteRuleParser.parse("payload: ['+.example.com', 'exact.test']").domains)
        assertEquals(listOf("192.0.2.0/24"), RemoteRuleParser.parse("payload: ['192.0.2.0/24']").ips)
    }
    @Test fun invalidOrEmptyPayloadDoesNotBecomeCatchAll() {
        for (bad in listOf("payload: []", "proxies: []", "payload: [1]", "payload: ['IP-CIDR,192.0.2.0/80']", "payload: ['MATCH,DIRECT']")) {
            try { RemoteRuleParser.parse(bad); fail(bad) } catch (_: IllegalStateException) { }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun urlValidationAndIndependentManualConditions() {
        assertEquals("https://example.com/rules.yaml", RemoteRuleParser.url(" https://example.com/rules.yaml "))
        for (bad in listOf("file:///tmp/a", "javascript:alert(1)", "https://u:p@example.com/a")) {
            try { RemoteRuleParser.url(bad); fail(bad) } catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
        val r = RuleEntity(domains = "full:manual.test", remoteDomains = "domain:remote.test", sourceUrl = "https://example.com/r", cachedRuleCount = 1)
        assertEquals("full:manual.test\ndomain:remote.test", RemoteRulePolicy.effective(r).domains)
        assertEquals("full:manual.test", r.domains)
        assertFalse(RemoteRulePolicy.waitingWithoutConditions(r))
        assertTrue(RemoteRulePolicy.waitingWithoutConditions(RuleEntity(sourceUrl = "https://example.com/r")))
    }
}
