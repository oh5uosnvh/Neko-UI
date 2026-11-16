package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.RemoteRuleParser
import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRuleParserTest {
    @Test
    fun parsesValidRulesAndReportsSkippedCount() {
        val result = RemoteRuleParser.parse("""
            payload:
              - DOMAIN,example.com
              - DOMAIN-SUFFIX,example.org
              - IP-CIDR,192.0.2.0/24
              - PROCESS-NAME,ignored
              - DOMAIN,bad/name
        """.trimIndent())
        assertEquals(3, result.count)
        assertEquals(2, result.skipped)
        assertTrue(result.domains.contains("full:example.com"))
        assertTrue(result.ips.contains("192.0.2.0/24"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPayloadWithoutOverwritingCache() {
        RemoteRuleParser.parse("payload: []")
    }

    @Test
    fun clashDomainAndIpRecordsCompileAsAlternativesAndEmptyCacheNeverMatchesAll() {
        val rule = RuleEntity(builtinKey = "remote_google", cachedRuleCount = 2,
            domains = "domain:example.com", ip = "192.0.2.0/24")
        val alternatives = rule.compiledAlternatives()
        assertEquals(2, alternatives.size)
        assertTrue(alternatives.any { it.domains.isNotBlank() && it.ip.isBlank() })
        assertTrue(alternatives.any { it.ip.isNotBlank() && it.domains.isBlank() })
        assertTrue(rule.copy(cachedRuleCount = 0).compiledAlternatives().isEmpty())
        assertEquals(1, rule.copy(builtinKey = "").compiledAlternatives().size)
    }

    @Test
    fun emptyRemoteRuleHasNoEffectiveCriteria() {
        val rule = RuleEntity(builtinKey = "remote_google", cachedRuleCount = 0)
        assertFalse(rule.hasEffectiveCriteria())
        assertTrue(rule.copy(domains = "full:example.com", cachedRuleCount = 1).hasEffectiveCriteria())
    }
}
