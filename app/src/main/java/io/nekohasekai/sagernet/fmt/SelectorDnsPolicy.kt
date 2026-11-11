package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.SubscriptionDns
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/** A selector changes outbounds, not the running application's DNS rule table.
 * Node resolvers are independently scoped; application DNS is a reload boundary.
 */
internal class SelectorDnsPolicy(private val globalDirect: List<String>) {
    val servers = mutableListOf<DNSServerOptions>()
    private val directResolvers = linkedMapOf<List<String>, String>()

    fun directAddresses(config: SubscriptionDns.Config, subscription: SubscriptionBean?): List<String> {
        val override = subscription?.directDnsResolver.orEmpty()
        return when {
            override.isNotBlank() -> SubscriptionDns.endpointList(override)
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
    fun generalSubscription(config: SubscriptionDns.Config): SubscriptionBean {
        val general = SubscriptionDns.Config().apply {
            hosts.putAll(config.hosts.toSortedMap())
            policy.putAll(config.policy.toSortedMap())
            if (policy.isNotEmpty()) bootstrap.addAll(config.bootstrap)
        }
        return SubscriptionBean().apply {
            initializeDefaultValues()
            importedDnsConfig = general.snapshot()
            importedDnsSource = "subscription"
        }
    }

    fun applicationFingerprint(config: SubscriptionDns.Config, subscription: SubscriptionBean?): String =
        SelectorFingerprint.policy(mapOf(
            "direct" to directAddresses(config, subscription),
            "hosts" to config.hosts,
            "policy" to config.policy,
            "bootstrap" to if (config.policy.isEmpty()) emptyList<String>() else config.bootstrap,
        ))
}
