package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import java.security.MessageDigest

/** Generates resolver-scoped options, never a global domain->node-DNS rule. */
class SubscriptionDnsBuilder {
    val servers = mutableListOf<DNSServerOptions>()
    private val built = linkedMapOf<String, String>()
    private val parsed = hashMapOf<List<String>, SubscriptionDns.Config>()
    // Canonical keys are generated from normalized fields, not YAML. Keeping
    // this per build avoids the old Config -> SnakeYaml dump on every member.
    private val canonical = java.util.IdentityHashMap<SubscriptionDns.Config, String>()

    fun config(bean: AbstractBean, subscription: SubscriptionBean?): SubscriptionDns.Config {
        val values = listOf(
            GroupDnsStore.importedForNode(bean, subscription),
            subscription?.privateDnsConfig ?: "",
            subscription?.hostsConfig ?: "",
            subscription?.serverDnsResolver ?: "",
        )
        // Selector groups can contain thousands of nodes with one shared subscription configuration.
        return parsed.getOrPut(values) {
            SubscriptionDns.effective(values[0], values[1], values[2], values[3])
        }
    }

    private fun objectServer(options: Map<String, Any>): DNSServerOptions =
        DNSServerOptions().apply { _hack_config_map.putAll(options) }

    private fun policyServer(
        tag: String,
        fallback: List<String>,
        rules: List<Map<String, Any>> = emptyList(),
        serverRules: List<Map<String, Any>> = emptyList(),
        hosts: Map<String, List<String>> = emptyMap(),
    ): String {
        servers += objectServer(linkedMapOf<String, Any>(
            "type" to "neko-policy", "tag" to tag, "fallback" to fallback,
            "rules" to rules, "server_rules" to serverRules, "hosts" to hosts,
            "timeout" to "2s",
        ))
        return tag
    }

    private fun endpointServers(addresses: List<String>, prefix: String, bootstrap: String): List<String> =
        addresses.mapIndexed { index, value ->
            val tag = "$prefix-$index"
            servers += objectServer(SubscriptionDns.endpoint(value).serverOptions(tag, bootstrap))
            tag
        }

    /** Group override of direct DNS; an empty list continues using the global direct DNS. */
    fun direct(addresses: List<String>, fallback: String = "dns-local"): String {
        val upstreams = endpointServers(addresses, "dns-sub-direct-up", fallback)
        return policyServer("dns-direct", upstreams)
    }

    fun node(bean: AbstractBean, subscription: SubscriptionBean?, fallback: String = "dns-direct"): String? =
        node(config(bean, subscription), fallback)

    /** Build directly from the already normalized per-build config.
     * Avoids the old Config -> YAML snapshot -> SnakeYaml parse round trip for
     * every selector member. The Config is owned by this build and immutable by
     * convention after effective() returns.
     */
    fun node(config: SubscriptionDns.Config, fallback: String = "dns-direct"): String? {
        // applyHosts only chooses the stored server address (A or B). The A->B DNS rule is always kept.
        if (!config.hasNodeConfig()) return null
        return build(config, true, fallback)
    }

    private fun build(config: SubscriptionDns.Config, node: Boolean, fallback: String): String {
        val identity = canonical.getOrPut(config) {
            io.nekohasekai.sagernet.fmt.SelectorFingerprint.policy(config.asDocument())
        } + "\n$node\n$fallback"
        built[identity]?.let { return it }
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val tag = "dns-sub-$hash"
        built[identity] = tag
        val bootstrap = if (config.bootstrap.isEmpty()) "dns-local" else {
            val upstreams = endpointServers(config.bootstrap, "$tag-bootstrap-up", "dns-local")
            policyServer("$tag-bootstrap", upstreams)
        }
        val default = if (node && config.serverDns.isNotEmpty()) {
            endpointServers(config.serverDns, "$tag-default", bootstrap)
        } else listOf(fallback)
        fun rules(values: Map<String, List<String>>, suffix: String) = values.entries.mapIndexed { index, (pattern, addresses) ->
            mapOf<String, Any>(
                "pattern" to pattern,
                "servers" to endpointServers(addresses, "$tag-$suffix-$index", bootstrap),
            )
        }
        return policyServer(
            tag, default, rules(config.policy, "policy"),
            if (node) rules(config.serverPolicy, "server") else emptyList(), config.hosts,
        )
    }

    /** Only the selected group's ordinary policy/hosts affect application DNS. */
    fun general(bean: AbstractBean, subscription: SubscriptionBean?, fallback: String): List<DNSRule_DefaultOptions> =
        general(config(bean, subscription), fallback)

    /** Build from the normalized config already held by this build. */
    fun general(config: SubscriptionDns.Config, fallback: String): List<DNSRule_DefaultOptions> {
        if (!config.hasGeneralConfig()) return emptyList()
        val resolver = build(config, false, fallback)
        val keys = (config.hosts.keys + config.policy.keys).distinct()
        if (keys.isEmpty()) return emptyList()
        return listOf(DNSRule_DefaultOptions().apply {
            server = resolver
            // Matching here only selects the transport; exact/suffix precedence is enforced there.
            domain_regex = keys.map { key ->
                when {
                    key.startsWith("+.") -> "(?i)^(?:.+\\.)?" + escapeRegex(key.drop(2)) + "\\.?$"
                    key.startsWith("*.") -> "(?i)^[^.]+\\." + escapeRegex(key.drop(2)) + "\\.?$"
                    key.startsWith(".") -> "(?i)^.+" + escapeRegex(key) + "\\.?$"
                    else -> "(?i)^" + escapeRegex(key) + "\\.?$"
                }
            }
        })
    }

    private fun escapeRegex(value: String): String = value.replace(".", "\\.").replace("-", "\\-")
}
