package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class RuntimeModeRulesTest {
    @Test fun modeGuardIsOutsideInvertedPredicate() {
        val source = mapOf<String, Any>(
            "domain_suffix" to listOf("example.test"), "invert" to true,
            "outbound" to "bypass"
        )
        val guarded = RuntimeModeRules.guard(source)
        assertEquals("logical", guarded["type"])
        assertEquals("and", guarded["mode"])
        assertEquals("bypass", guarded["outbound"])
        val children = guarded["rules"] as List<*>
        assertEquals(mapOf("clash_mode" to "Rule"), children[0])
        val predicate = children[1] as Map<*, *>
        assertEquals(true, predicate["invert"])
        assertFalse(predicate.containsKey("outbound"))
        assertEquals("bypass", source["outbound"])
    }

    @Test fun rejectActionStaysOnOuterRule() {
        val guarded = RuntimeModeRules.guard(mapOf("action" to "reject", "port" to listOf(443)))
        assertEquals("reject", guarded["action"])
        val predicate = (guarded["rules"] as List<*>)[1] as Map<*, *>
        assertFalse(predicate.containsKey("action"))
        assertEquals(listOf(443), predicate["port"])
    }

    @Test fun dnsActionsAreNotPredicates() {
        val guarded = RuntimeModeRules.guard(mapOf(
            "domain" to listOf("dns.test"), "server" to "dns-direct",
            "disable_cache" to true, "rewrite_ttl" to 60
        ), dns = true)
        assertEquals("dns-direct", guarded["server"])
        assertEquals(true, guarded["disable_cache"])
        assertEquals(60, guarded["rewrite_ttl"])
        val predicate = (guarded["rules"] as List<*>)[1] as Map<*, *>
        assertEquals(setOf("domain"), predicate.keys)
    }

    @Test fun catchAllRemainsValidModeOnlyMatch() {
        val guarded = RuntimeModeRules.guard(mapOf("outbound" to "proxy"))
        assertEquals(listOf(mapOf("clash_mode" to "Rule")), guarded["rules"])
    }

    @Test fun logicalPredicateRetainsItsOwnTypeAndInversion() {
        val guarded = RuntimeModeRules.guard(mapOf(
            "type" to "logical", "mode" to "or", "invert" to true,
            "rules" to listOf(mapOf("domain" to listOf("a.test"))),
            "outbound" to "bypass"
        ))
        val predicate = (guarded["rules"] as List<*>)[1] as Map<*, *>
        assertEquals("logical", predicate["type"])
        assertEquals("or", predicate["mode"])
        assertEquals(true, predicate["invert"])
    }
}
