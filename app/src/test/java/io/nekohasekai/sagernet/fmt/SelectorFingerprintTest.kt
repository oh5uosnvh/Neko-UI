package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorFingerprintTest {
    private fun config(selected: String = "a", dns: String = "dns1", route: String = "bypass"): Map<String, Any?> = mapOf(
        "outbounds" to listOf(mapOf("type" to "selector", "tag" to "proxy", "default" to selected,
            "outbounds" to listOf("a", "b"))),
        "dns" to mapOf("final" to dns), "route" to mapOf("final" to route)
    )

    @Test fun completePolicyIgnoresOnlyInitialChoiceAndMapKeyOrder() {
        val a = SelectorFingerprint.policy(config("a"))
        assertEquals(a, SelectorFingerprint.policy(config("b")))
        assertEquals(a, SelectorFingerprint.policy(config().entries.reversed().associate { it.toPair() }))
    }

    @Test fun dnsAndRouteChangesInvalidateRunningPolicy() {
        val a = SelectorFingerprint.policy(config())
        assertNotEquals(a, SelectorFingerprint.policy(config(dns = "dns2")))
        assertNotEquals(a, SelectorFingerprint.policy(config(route = "proxy")))
    }

    @Test fun sameCrossGroupTopologyIsOrderIndependent() {
        val left = SelectorFingerprint.of(
            listOf("group:1:dns:shared", "node:10:group:1:chain:10,11:config:a", "node:20:group:2:chain:20:config:b")
        )
        val right = SelectorFingerprint.of(
            listOf("node:20:group:2:chain:20:config:b", "group:1:dns:shared", "node:10:group:1:chain:10,11:config:a")
        )
        assertEquals(left, right)
    }

    @Test fun persistedMemberEditInvalidatesFingerprint() {
        val old = SelectorFingerprint.of(listOf("node:10:group:1:chain:10:config:a"))
        val edited = SelectorFingerprint.of(listOf("node:10:group:1:chain:10:config:edited"))
        assertNotEquals(old, edited)
    }

    @Test fun runtimeSelectionAndPortAreNotFingerprintInputs() {
        val old = SelectorFingerprint.of(listOf("node:10:group:1:chain:10:config:a"))
        val selectedAgain = SelectorFingerprint.of(listOf("node:10:group:1:chain:10:config:a"))
        assertEquals(old, selectedAgain)
    }
}
