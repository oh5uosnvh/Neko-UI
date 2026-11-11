package io.nekohasekai.sagernet.fmt

/**
 * Keep the mode guard outside the original predicate (especially invert).
 * Actions belong to the outer rule: nested rules are predicates only.
 */
internal object RuntimeModeRules {
    private val routeActions = setOf(
        "action", "outbound", "override_address", "override_port",
        "network_strategy", "fallback_delay", "udp_disable_domain_unmapping",
        "udp_connect", "udp_timeout", "method", "no_drop", "reject_method"
    )
    private val dnsActions = setOf(
        "action", "server", "disable_cache", "rewrite_ttl", "client_subnet",
        "strategy", "rcode", "answer", "ns", "extra"
    )

    fun guard(original: Map<String, Any>, dns: Boolean = false): Map<String, Any> {
        val predicate = original.toMutableMap()
        val result = linkedMapOf<String, Any>("type" to "logical", "mode" to "and")
        (if (dns) dnsActions else routeActions).forEach { key ->
            predicate.remove(key)?.let { result[key] = it }
        }
        // An otherwise empty user predicate means match-all in Rule mode.
        predicate.remove("type")?.let { if (it == "logical") predicate["type"] = it }
        result["rules"] = if (predicate.isEmpty()) {
            listOf(mapOf("clash_mode" to "Rule"))
        } else {
            listOf(mapOf("clash_mode" to "Rule"), predicate)
        }
        return result
    }
}
