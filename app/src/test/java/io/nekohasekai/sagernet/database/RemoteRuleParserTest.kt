package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRuleParserTest {
    @Test fun mixedValidAndInvalidEntriesKeepValidRules() {
        val result = RemoteRuleParser.parse(
            """
            payload:
              - DOMAIN,Example.COM
              - DOMAIN-SUFFIX,example.org
              - IP-CIDR,192.0.2.0/24
              - IP-CIDR,not-an-ip/24
              - PROCESS-NAME,ignored
              - DOMAIN,
            """.trimIndent()
        )
        assertEquals(3, result.count)
        assertEquals(listOf("full:example.com", "domain:example.org"), result.domains)
        assertEquals(listOf("192.0.2.0/24"), result.ips)
        assertEquals(3, result.skipped)
        assertTrue(result.skippedReason.contains("invalid IP-CIDR"))
        assertTrue(result.skippedReason.contains("unsupported PROCESS-NAME"))
    }

    @Test fun malformedPayloadDoesNotBecomeWildcardRule() {
        try {
            RemoteRuleParser.parse("payload:\n  - PROCESS-NAME,ignored\n  - DOMAIN,\n")
            throw AssertionError("expected no usable rules")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("no usable rules"))
        }
    }

    @Test fun failedRefreshStateLeavesPreviousCacheUntouched() {
        val cached = RuleEntity(
            cachedRuleCount = 2,
            domains = "full:keep.example",
            ip = "192.0.2.0/24",
            subscriptionState = "ready",
        )
        val failed = cached.copy(
            subscriptionState = if (cached.cachedRuleCount > 0) "stale" else "waiting",
            lastUpdateError = "remote update failed",
        )
        assertEquals(cached.cachedRuleCount, failed.cachedRuleCount)
        assertEquals(cached.domains, failed.domains)
        assertEquals(cached.ip, failed.ip)
        assertEquals("stale", failed.subscriptionState)
    }
}
