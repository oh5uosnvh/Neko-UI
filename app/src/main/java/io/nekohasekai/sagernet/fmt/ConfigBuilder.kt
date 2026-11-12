package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.oppa.OppaBean
import io.nekohasekai.sagernet.fmt.oppa.buildSingBoxOutboundOppaBean
import moe.matsuri.nb4a.proxy.xhttp.XhttpBean
import moe.matsuri.nb4a.proxy.xhttp.buildSingBoxOutboundXhttpBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.group.SubscriptionDns
import io.nekohasekai.sagernet.group.SubscriptionDnsBuilder
import io.nekohasekai.sagernet.group.NodeHostMapping
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"
/** Stable selector scope marker; group IDs must not change during global live switches. */
const val GLOBAL_SELECTOR_GROUP_ID = 0L

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    /** True when all enabled groups contribute to the runtime selector. */
    val globalSelector: Boolean = false,
    /** Application DNS is separate from the immutable node resolver graph. */
    val selectorApplicationDnsFingerprint: String = "",
    /** Fingerprint of selector membership, chains and DNS policy at build time. */
    val selectorFingerprint: String = "",
    /** Allocated loopback ports keyed by this build, values are stable chain identities. */
    val runtimePorts: Map<Int, String> = emptyMap(),
    /** Database selector-graph generation captured when this snapshot was built. */
    val selectorGeneration: Long = SelectorGeneration.current(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false,
    disableSelector: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {

            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    // All of these caches are deliberately scoped to one buildConfig call. They
    // never outlive the Room snapshot, so they cannot become the stale mutable
    // cross-start cache that ConfigBuildCache used to be.
    val groupCache = HashMap<Long, ProxyGroup?>()
    val entityCache = HashMap<Long, ProxyEntity>()
    val resolvedChainCache = HashMap<Long, List<ProxyEntity>>()
    val internalChainCache = HashMap<Long, List<ProxyEntity>>()
    val beanJsonCache = HashMap<Long, String>()
    groupCache[proxy.groupId] = group
    entityCache[proxy.id] = proxy
    fun cachedEntity(id: Long): ProxyEntity? = entityCache[id] ?:
        SagerDatabase.proxyDao.getById(id)?.also { entityCache[id] = it }
    fun cachedGroup(id: Long): ProxyGroup? = groupCache[id] ?:
        SagerDatabase.groupDao.getById(id).also { groupCache[id] = it }

    val resolvingChains = hashSetOf<Long>()
    fun ProxyEntity.resolveChainInternal(): List<ProxyEntity> {
        internalChainCache[id]?.let { return it }
        val bean = requireBean()
        if (bean !is ChainBean) return listOf(this).also { internalChainCache[id] = it }
        require(resolvingChains.add(id)) { "Cyclic proxy chain: $id" }
        try {
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = cachedEntity(proxyId) ?: error("Missing proxy chain member: $proxyId")
                beanList.addAll(item.resolveChainInternal())
            }
            require(beanList.isNotEmpty()) { "Empty proxy chain: $id" }
            return beanList.asReversed().also { internalChainCache[id] = it }
        } finally { resolvingChains.remove(id) }
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): List<ProxyEntity> {
        resolvedChainCache[id]?.let { return it }
        val thisGroup = cachedGroup(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { cachedEntity(it) }
        val landingProxy = thisGroup?.landingProxy?.let { cachedEntity(it) }
        val list = ArrayList(resolveChainInternal())
        if (frontProxy != null) list.add(frontProxy)
        if (landingProxy != null) list.add(0, landingProxy)
        return list.also { resolvedChainCache[id] = it }
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
        .filterNot { io.nekohasekai.sagernet.database.RemoteRulePolicy.waitingWithoutConditions(it) }
        .map { io.nekohasekai.sagernet.database.RemoteRulePolicy.effective(it) }
    // buildSelector requires either the per-group switch or the pure global
    // option (DataStore.globalSelectorAllGroups): then the members of the
    // SELECTED group compile into its runtime selector on the next connection.
    // forTest/forExport stay outside the option: test/export configs never
    // compile a runtime selector, regardless of the group switch.
    // The global option NEVER unions other groups' nodes: startup stays scoped
    // to one group's nodes so VPN connect does not pay for the whole catalog.
    val globalSelectorAllGroups = !forTest && !forExport && DataStore.globalSelectorAllGroups
    val buildSelector = !forTest && !forExport && !disableSelector &&
        (group?.isSelector == true || globalSelectorAllGroups)
    val selectorMembers = if (buildSelector) {
        // Members come from the selected group only. Cross-group switching is
        // not compiled into this core: every group keeps its own small selector
        // graph, so cold start loads one group's nodes, not the full catalog.
        val selectedGroupId = group?.id ?: 0L
        val selectedGroup = cachedGroup(selectedGroupId)
        val members = if (selectedGroup != null) {
            SagerDatabase.proxyDao.getByGroup(selectedGroupId)
                .orEmpty()
                .sortedBy { member -> member.id }
        } else emptyList()
        members.filter { member ->
            val bean = member.requireBean()
            // Whole-core configurations cannot be nested as an outbound. Selecting one reconnects.
            bean !is ConfigBean || bean.type != 0
        }
    } else emptyList()
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val subscriptionDns = SubscriptionDnsBuilder()
    val activeSubscription = group?.subscription
    val activeDns = subscriptionDns.config(proxy.requireBean(), activeSubscription)
    val directOverride = activeSubscription?.directDnsResolver.orEmpty()
    val subscriptionDirectDns = if (directOverride.isNotBlank()) SubscriptionDns.endpointList(directOverride) else activeDns.directDns
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val deviceInboundTag = if (isVPN && DataStore.enableHevTun) TAG_MIXED else "tun-in"
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val selectorDns = SelectorDnsPolicy(directDNS)
    val dnsHosts by lazy { parseDnsHosts(DataStore.dnsHosts) }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val selectorFingerprintParts = mutableListOf<String>()
    val selectorDirectChoices = linkedMapOf<String, String>()
    val selectorGeneralChoices = linkedMapOf<String, String>()
    val selectorGeneralRules = mutableListOf<DNSRule_DefaultOptions>()
    val runtimePorts = linkedMapOf<Int, String>()
    fun runtimePort(identity: String): Int = mkPort().also { runtimePorts[it] = identity }
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
	if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (DataStore.enableClashAPI) {
                    store_fakeip = true
                    // }
                }
                
                if (DataStore.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN && !DataStore.enableHevTun) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                interface_name = "tun0"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                auto_route = true
                strict_route = DataStore.strictRoute
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }

                    IPv6Mode.ONLY -> {
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }

                    else -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                if (DataStore.mixedInboundHasAuth) {
                    users = listOf(User().also { u ->
                        u.username = DataStore.mixedUsername
                        u.password = DataStore.mixedSecret
                    })
                }
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 添加并发拨号设置
             concurrent_dial = DataStore.concurrentDial
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain().map { hop ->
                hop.copy().apply { putBean(hop.requireBean().clone()) }
            }
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                val nodeGroup = groupCache.getOrPut(proxyEntity.groupId) {
                    SagerDatabase.groupDao.getById(proxyEntity.groupId)
                }
                val nodeSubscription = nodeGroup?.subscription
                val nodeDnsConfig = subscriptionDns.config(bean, nodeSubscription)
                NodeHostMapping.apply(bean, nodeDnsConfig.hosts, nodeSubscription?.applyHosts != false)
                val fallbackResolver = if (buildSelector) selectorDns.nodeFallback(nodeDnsConfig, nodeSubscription) else "dns-direct"
                // nodeDnsConfig is already normalized and cached for this build;
                // do not serialize it to YAML only to parse it back again.
                val nodeResolver = subscriptionDns.node(nodeDnsConfig, fallbackResolver)
                    ?: if (buildSelector) fallbackResolver else null
                val preserveMultiAddress = nodeSubscription?.applyHosts != false && NodeHostMapping.hasMultipleAddresses(bean, nodeDnsConfig.hosts)
                // Keep all IP candidates in the resolver instead of freezing a multi-IP hosts entry.
                if (preserveMultiAddress) bean.serverAddress = bean.mappedOriginalAddress
                if (nodeResolver != null && proxyEntity.needExternal() && !bean.canMapping()) {
                    throw IllegalArgumentException("This external protocol cannot use subscription DNS safely; use a built-in protocol or disable its subscription DNS settings")
                }

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = if (buildSelector) {
                        if (profileList.size == 1) "s-$chainId-${proxyEntity.id}" else "g-${proxyEntity.id}"
                    } else "g-${proxyEntity.id}"
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                if (index == 0) {
                    tagOut = if (buildSelector) "s-$chainId-${proxyEntity.id}" else readableTag(bean.displayName())
                }

                val shareGlobal = needGlobal && !(buildSelector && profileList.size == 1)
                val existingGlobal = if (shareGlobal) globalOutbounds[proxyEntity.id] else null
                if (existingGlobal != null) tagOut = existingGlobal

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (shareGlobal) {
                    if (existingGlobal != null) {
                        if (index == 0) chainTagOut = existingGlobal
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = runtimePort("external:$chainId:${proxyEntity.id}")
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean ->
                            buildSingBoxOutboundWireguardBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is XhttpBean ->
                            buildSingBoxOutboundXhttpBean(bean)

                        is OppaBean ->
                            buildSingBoxOutboundOppaBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            currentOutbound._hack_config_map["detour"] = TAG_FRAGMENT
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy
                    if (nodeResolver != null && !proxyEntity.needExternal()) {
                        _hack_config_map["domain_resolver"] = mapOf(
                            "server" to nodeResolver,
                            "strategy" to genDomainStrategy(true),
                        )
                    }

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId) && nodeResolver == null) {
                            needExternal = false
                        } else if (!Plugins.isUsingMatsuriExe(pluginId) && Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = runtimePort("mapping:$chainId:${proxyEntity.id}")
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag
                            if (nodeResolver != null) {
                                // Resolve before routing a plugin's mapping inbound; applies to every chain hop.
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    action = "resolve"
                                    _hack_config_map["server"] = nodeResolver
                                    _hack_config_map["strategy"] = genDomainStrategy(true)
                                })
                            }

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                if (DataStore.enableTLSFragment) {
                                    route.rules.add(Rule_DefaultOptions().apply {
                                        network = listOf("tcp")
                                        inbound = listOf(tag)
                                        outbound = TAG_FRAGMENT
                                    })
                                }

                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // Build one selector over every group when the global switch is enabled.
        // Each member still enters through resolveChain(), which applies its own
        // group's front/landing proxies and subscription-scoped node DNS.
        if (buildSelector) {
            val members = selectorMembers
            if (members.none { it.id == proxy.id }) {
                throw IllegalArgumentException("Selected node is not a member of the compiled selector")
            }
            members.forEach {
                val chain = it.resolveChain()
                selectorFingerprintParts += buildString {
                    append(it.id).append(':').append(it.groupId).append(':')
                    append(chain.joinToString(",") { member -> member.id.toString() }).append(':')
                    // Include every chain hop's persisted config, not only the
                    // selected node. Front/landing edits must invalidate a live switch.
                    append(chain.joinToString("|") { member ->
                        beanJsonCache.getOrPut(member.id) { gson.toJson(member.requireBean()) }
                    })
                    val bean = it.requireBean()
                    append(':').append(bean.javaClass.name).append(':')
                    // Include the selected node config, not only its ID/type;
                    // edits to a compiled member must invalidate live switching.
                    append(beanJsonCache.getOrPut(it.id) { gson.toJson(bean) })
                }
                tagMap[it.id] = buildChain(it.id, it)
                val memberGroup = cachedGroup(it.groupId)
                val memberDns = subscriptionDns.config(it.requireBean(), memberGroup?.subscription)
                val memberTag = tagMap.getValue(it.id)
                selectorDirectChoices[memberTag] = selectorDns.nodeFallback(memberDns, memberGroup?.subscription)
                val generalConfig = selectorDns.generalConfig(memberDns)
                val rules = subscriptionDns.general(generalConfig, "dns-remote")
                // A common dispatch rule table, not a union of group hosts. If the selected
                // group has no matching entry, only that group's resolver falls back to remote.
                selectorGeneralChoices[memberTag] = rules.firstOrNull()?.server ?: "dns-remote"
                selectorGeneralRules += rules
            }
            val memberTags = members.mapNotNull { tagMap[it.id] }.distinct()
            if (memberTags.isEmpty()) {
                throw IllegalArgumentException("Selector has no compilable members")
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = memberTags
                // A selector change must retire every existing inbound flow
                // synchronously inside sing-box. Android must not emulate this
                // with a delayed global conntrack.Close(), which can kill the
                // first connection of the newly selected node.
                interrupt_exist_connections = true
            })
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        val extraProxyIds = (if (buildSelector) SagerDatabase.rulesDao.allRules() else extraRules).mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && (buildSelector || it != proxy.id) }
        }.toSet()
        SagerDatabase.proxyDao.getEntities(extraProxyIds.toList()).sortedBy { it.id }.forEach { p ->
            if (p.id !in tagMap) tagMap[p.id] = buildChain(p.id, p)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        // 在应用用户规则之前检查全局模式
        if (!forTest && DataStore.globalMode) {
            // 全局模式下的规则处理
            
            // 绕过内部网络（如果启用）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "224.0.0.0/3",
                        "172.16.0.0/12",
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                    )
                    outbound = TAG_DIRECT
                })
            }

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(deviceInboundTag)
                outbound = mainProxyTag
            })

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }
                    
                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                    
		    // 存储ruleset标签和类型信息
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                    
                    // 处理远程ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)
                            
                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)
                            
                            rulesetTags.add(Pair(tag, isIPRuleset))
                            
                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                        return DNSRule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) user_id = uidList
                            domainList?.let { makeSingBoxRule(it) }
                        }
                    }

                    val hasDomainCriteria = !domainList.isNullOrEmpty()
                    val hasIpCriteria =
                        rule.ip.isNotBlank() || rulesetTags.any { it.second }
                    val hasDomainRuleset = rulesetTags.any { !it.second }
                    val isAppOnlyDns =
                        uidList.isNotEmpty() &&
                            !hasDomainCriteria &&
                            !hasIpCriteria &&
                            !hasDomainRuleset &&
                            rule.port.isBlank() &&
                            rule.sourcePort.isBlank() &&
                            rule.network.isBlank() &&
                            rule.source.isBlank() &&
                            rule.protocol.isBlank()
                    val shouldAddDnsRule = hasDomainCriteria || isAppOnlyDns

                    when (rule.outbound) {
                        -1L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // 只处理ruleset标签，且必须是非IP类型
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-direct"
                                        }
                                    }
                                }
                            }
                        }

                        0L -> {
                            if (shouldAddDnsRule) {
                                if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf(deviceInboundTag)
                                    query_type = listOf("A", "AAAA")
                                } else {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-remote"
                                    }
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        if (useFakeDns) {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-fake"
                                                inbound = listOf(deviceInboundTag)
                                                query_type = listOf("A", "AAAA")
                                            }
                                        } else {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-remote"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        -2L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-block"
                                    disable_cache = true
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-block"
                                            disable_cache = true
                                        }
                                    }
                                }
                            }
                        }
                    }

                    outbound = when (val outId = rule.outbound) {
                        0L -> mainProxyTag
                        -1L -> TAG_BYPASS
                        -2L -> TAG_BLOCK
                        else -> if (!buildSelector && outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                    }

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // block 改用新的写法
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        if (DataStore.enableTLSFragment) {
            val fragmentOutbound = Outbound().apply {
                tag = TAG_FRAGMENT
                type = "direct"
                _hack_config_map["fragment"] = Fragment().apply {
                    length = DataStore.fragmentLength
                    interval = DataStore.fragmentInterval
                }.asMap()
            }
            outbounds.add(fragmentOutbound)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = "dns-block"
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        if (buildSelector) {
            // Every node resolver exists before the dispatcher and before legacy remote
            // address resolution. No node bootstrap depends on the dynamic dns-direct tag.
            dns.servers.addAll(selectorDns.servers)
            dns.servers.addAll(subscriptionDns.servers)
            subscriptionDns.servers.clear()
            dns.servers.add(selectorDns.dispatch("dns-direct", selectorDirectChoices))
        } else if (subscriptionDirectDns.isNotEmpty()) {
            subscriptionDns.direct(subscriptionDirectDns)
            // Legacy remote DNS resolves its address_resolver during construction, not Start.
            // Materialize dns-direct before dns-remote so a group override is already available.
            dns.servers.addAll(subscriptionDns.servers)
            subscriptionDns.servers.clear()
        } else directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No direct DNS, check your settings!")
                tag = "dns-direct"
                detour = TAG_DIRECT
                address_resolver = "dns-local"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No remote DNS, check your settings!")
                tag = "dns-remote"
                address_resolver = "dns-direct"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                tag = TAG_DNS_HOSTS
                _hack_config_map["type"] = "hosts"
                _hack_config_map["predefined"] = dnsHosts
            })
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                    strategy = "ipv4_only"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf(deviceInboundTag)
                    server = "dns-fake"
                    disable_cache = true
                    query_type = listOf("A", "AAAA")
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    _hack_config_map["ip_accept_any"] = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toSortedSet().toList())
                    server = "dns-direct"
                })
            }
            // Subscription policy is scoped to the active group. Only matched domains override app DNS.
            val subscriptionRules = if (buildSelector) {
                dns.servers.add(selectorDns.dispatch("dns-selector-general", selectorGeneralChoices))
                selectorGeneralRules.onEach { rule ->
                    rule.server = "dns-selector-general"
                    // sing-box caches by transport tag. A dynamic dispatcher must not
                    // reuse a previous group's answer, including in-flight responses.
                    rule.disable_cache = true
                }.distinctBy { gson.toJson(it.asMap()) }
            } else subscriptionDns.general(proxy.requireBean(), activeSubscription, "dns-remote")
            dns.rules.addAll(0, subscriptionRules)
            if (buildSelector) {
                // dns-direct dispatch is shared by user direct rules and node-domain
                // guards, so disable that dynamic tag's cache too.
                dns.rules.filterIsInstance<DNSRule_DefaultOptions>()
                    .filter { it.server == "dns-direct" }.forEach { it.disable_cache = true }
            }
        }

        dns.servers.addAll(subscriptionDns.servers)
        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) GLOBAL_SELECTOR_GROUP_ID else -1L,
            globalSelector = buildSelector,
            selectorApplicationDnsFingerprint = if (buildSelector)
                SelectorFingerprint.policy(mapOf("direct" to selectorDirectChoices, "general" to selectorGeneralChoices)) else "",
            selectorFingerprint = if (buildSelector) {
                SelectorFingerprint.of(selectorFingerprintParts + SelectorFingerprint.policy(configMap, runtimePorts))
            } else "",
            runtimePorts = runtimePorts.toMap(),
        )
    }

}
