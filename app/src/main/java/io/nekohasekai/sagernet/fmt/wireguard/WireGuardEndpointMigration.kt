package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.utils.Util

/** 1.14 removed WireGuard outbounds; endpoint tags remain usable in routing,
 * selector membership and detour chains. Run after custom outbound merging. */
internal fun migrateWireGuardEndpoints(config: MutableMap<String, Any>) {
    val raw = config["outbounds"] as? List<*> ?: return
    val endpoints = ((config["endpoints"] as? List<*>) ?: emptyList<Any>()).toMutableList()
    val outbounds = mutableListOf<Any>()
    raw.forEach { value ->
        val source = value as? Map<*, *>
        if (source?.get("type") != "wireguard") {
            if (value != null) outbounds.add(value)
            return@forEach
        }
        val target = linkedMapOf<String, Any>("type" to "wireguard")
        val removed = setOf("server", "server_port", "peer_public_key", "pre_shared_key", "reserved", "peers", "local_address", "system_interface", "interface_name", "network")
        source.forEach { (key, item) -> if (key is String && key !in removed && item != null) target[key] = item }
        source["system_interface"]?.let { target["system"] = it }
        source["interface_name"]?.let { target["name"] = it }
        source["local_address"]?.let { target["address"] = it }
        fun reserved(value: Any?): Any? = if (value is String && value.isNotBlank()) {
            Util.b64Decode(value).also { require(it.size == 3) { "WireGuard reserved must contain three bytes" } }.map { it.toInt() and 255 }
        } else value
        fun peer(map: Map<*, *>): MutableMap<String, Any> {
            val p = linkedMapOf<String, Any>()
            map.forEach { (key, item) -> if (key is String && item != null && key !in setOf("server", "server_port", "reserved")) p[key] = item }
            map["server"]?.let { p["address"] = it }
            map["server_port"]?.let { p["port"] = it }
            reserved(map["reserved"])?.takeUnless { it == "" }?.let { p["reserved"] = it }
            if (p["allowed_ips"] == null) p["allowed_ips"] = listOf("0.0.0.0/0", "::/0")
            return p
        }
        val peers = (source["peers"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::peer) }?.toMutableList() ?: mutableListOf()
        if (source["server"] != null || source["peer_public_key"] != null) {
            peers.add(peer(linkedMapOf(
                "address" to source["server"], "port" to source["server_port"],
                "public_key" to source["peer_public_key"], "pre_shared_key" to source["pre_shared_key"],
                "reserved" to source["reserved"]
            )))
        }
        target["peers"] = peers
        endpoints.add(target)
    }
    config["outbounds"] = outbounds
    if (endpoints.isNotEmpty()) config["endpoints"] = endpoints
}
