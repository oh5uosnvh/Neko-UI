package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.SubscriptionDns
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/** A selector must never borrow the selected group's node resolver.
 * Immutable resolver graphs are compiled once and dispatch uses native selector.Now().
 */
internal class SelectorDnsPolicy(private val globalDirect: List<String>) {
    val servers = mutableListOf<DNSServerOptions>()
    private val directResolvers = linkedMapOf<List<String>, String>()
    private val directOverrides = hashMapOf<String, List<String>>()
    private val generalConfigs = java.util.IdentityHashMap<SubscriptionDns.Config, SubscriptionDns.Config>()

    fun directAddresses(config: SubscriptionDns.Config, subscription: SubscriptionBean?): List<String> {
        val override = subscription?.directDnsResolver.orEmpty()
        return when {
            override.isNotBlank() -> directOverrides.getOrPut(override) { SubscriptionDns.endpointList(override) }
            config.directDns.isNotEmpty() -> config.directDns
            else -> globalDirect.take(1) // Match the app's existing global direct-DNS fallback.
        }
    }

    /** Never fall back to dns-direct here: that tag belongs to the ACTIVE node's policy. */
    fun nodeFallback(config: SubscriptionDns.Config, subscription: SubscriptionBean?): String {
        val addresses = directAddresses(config, subscription).toList()
        require(addresses.isNotEmpty()) { "No direct DNS, check your settings!" }
        return directResolvers.getOrPut(addresses) {
            val tag = "dns-selector-direct-${SelectorFingerprint.policy(mapOf("direct" to addresses)).take(24)}"
            val upstreams = addresses.mapIndexed { index, address ->
                val upstream = "$tag-up-$index"
                servers += DNSServerOptions().apply {
                    _hack_config_map.putAll(SubscriptionDns.endpoint(address).serverOptions(upstream, "dns-local"))
                }
                upstream
            }
            servers += DNSServerOptions().apply {
                _hack_config_map.putAll(linkedMapOf<String, Any>(
                    "type" to "neko-policy", "tag" to tag, "fallback" to upstreams,
                    "rules" to emptyList<Any>(), "server_rules" to emptyList<Any>(),
                    "hosts" to emptyMap<String, Any>(), "timeout" to "2s",
                ))
            }
            tag
        }
    }

    /** Strip node-only fields before generating ordinary DNS transports.
     * Otherwise server-DNS-only differences also change the general resolver tag.
     * Sort maps so equivalent YAML order/formatting does not force a reload.
     */
    fun generalConfig(config: SubscriptionDns.Config): SubscriptionDns.Config =
        generalConfigs.getOrPut(config) {
            SubscriptionDns.Config().apply {
                hosts.putAll(config.hosts.toSortedMap())
                policy.putAll(config.policy.toSortedMap())
                if (policy.isNotEmpty()) bootstrap.addAll(config.bootstrap)
            }
        }

    /** Compatibility form for callers that persist a DNS editor snapshot. */
    fun generalSubscription(config: SubscriptionDns.Config): SubscriptionBean {
        val general = generalConfig(config)
        return SubscriptionBean().apply {
            initializeDefaultValues()
            importedDnsConfig = general.snapshot()
            importedDnsSource = "subscription"
        }
    }

    /** Node policy is already normalized by SubscriptionDns.effective(). */
    fun nodeConfig(config: SubscriptionDns.Config): SubscriptionDns.Config = config

    /** Canonical node-only configuration; YAML insertion order is not runtime identity. */
    fun nodeSubscription(config: SubscriptionDns.Config): SubscriptionBean {
        val node = SubscriptionDns.Config().apply {
            hosts.putAll(config.hosts.toSortedMap())
            policy.putAll(config.policy.toSortedMap())
            serverPolicy.putAll(config.serverPolicy.toSortedMap())
            bootstrap.addAll(config.bootstrap)
            serverDns.addAll(config.serverDns)
            directDns.addAll(config.directDns)
        }
        return SubscriptionBean().apply {
            initializeDefaultValues()
            importedDnsConfig = node.snapshot()
            importedDnsSource = "subscription"
        }
    }

    fun dispatch(tag: String, choices: Map<String, String>): DNSServerOptions = DNSServerOptions().apply {
        require(choices.isNotEmpty()) { "Empty selector DNS choices" }
        _hack_config_map.putAll(mapOf("type" to "neko-selector", "tag" to tag,
            "selector" to "proxy", "choices" to choices.toSortedMap()))
    }

    fun applicationFingerprint(config: SubscriptionDns.Config, subscription: SubscriptionBean?): String =
        SelectorFingerprint.policy(mapOf(
            "direct" to directAddresses(config, subscription),
            "hosts" to config.hosts,
            "policy" to config.policy,
            "bootstrap" to if (config.policy.isEmpty()) emptyList<String>() else config.bootstrap,
        ))
}
