package moe.matsuri.nb4a

/** Versioned, deterministic JNI wire model. No Android/JNI classes: tested on JVM. */
internal data class PlatformNetworkRecord(
    val id: String,
    val name: String,
    val index: Int,
    val mtu: Int,
    val flags: Int,
    val addresses: List<String>,
    val type: String,
    val dnsServers: List<String>,
    val dnsSearchDomains: List<String>,
    val gateways: List<String>,
    val metered: Boolean,
    val vpn: Boolean,
    val internet: Boolean,
    val validated: Boolean,
)

internal object AndroidNetworkSnapshot {
    // Go net.Flags bits, not android.system.OsConstants.IFF_*.
    const val UP = 1
    const val LOOPBACK = 4
    const val POINT_TO_POINT = 8
    const val MULTICAST = 16
    const val RUNNING = 32

    fun protectionRequired(vpnServicePresent: Boolean, networkVPNStates: () -> List<Boolean>): Boolean =
        vpnServicePresent || networkVPNStates().any { it }

    fun physical(record: PlatformNetworkRecord): Boolean =
        !record.vpn && record.internet && record.flags and UP != 0 &&
            record.flags and LOOPBACK == 0 && record.index > 0 && record.mtu > 0 &&
            record.addresses.isNotEmpty() && !record.name.startsWith("tun") &&
            !record.name.startsWith("utun")

    fun selectDefault(
        records: List<PlatformNetworkRecord>, underlyingId: String?, activeId: String?,
    ): PlatformNetworkRecord? {
        val physical = records.filter(::physical)
        // Listener identifies the underlying route even when activeNetwork is VPN.
        physical.firstOrNull { it.id == underlyingId }?.let { return it }
        physical.firstOrNull { it.id == activeId }?.let { return it }
        // Synchronous bootstrap in BOTH processes, before listeners deliver their
        // first event (also API 21/22). Rank framework networks, never Java's first
        // up interface. Prefer validated, unmetered Wi-Fi/Ethernet; stable tie-break.
        // On API 23+ an unvalidated network is not a safe bootstrap guess.
        // Listener/active identities above remain authoritative even if captive.
        return physical.filter { it.validated }.sortedWith(compareBy<PlatformNetworkRecord> { it.metered }
            .thenBy { when (it.type) { "ethernet", "wifi" -> 0; "cellular" -> 1; else -> 2 } }
            .thenBy { it.id }).firstOrNull()
    }

    fun encode(
        records: List<PlatformNetworkRecord>, underlyingId: String?, activeId: String?,
    ): String {
        require(records.isNotEmpty()) { "Android network snapshot has no interfaces" }
        records.forEach {
            require(it.name.isNotBlank() && it.index > 0 && it.mtu > 0 && it.addresses.isNotEmpty()) {
                "Android interface ${it.name} has incomplete metadata"
            }
        }
        val selected = selectDefault(records, underlyingId, activeId)
        // One Linux interface may back several Android Network identities.
        // Choose its selected/current metadata, never retain an old name/index.
        val interfaces = records.groupBy { it.name }.values.map { candidates ->
            candidates.firstOrNull { it.id == selected?.id } ?: candidates.sortedBy { it.id }.first()
        }.sortedWith(compareBy<PlatformNetworkRecord> { it.index }.thenBy { it.name })
        require(interfaces.map { it.index }.distinct().size == interfaces.size) {
            "Android network snapshot has duplicate interface indexes"
        }
        return "{\"version\":1,\"defaultInterface\":" + quote(selected?.name.orEmpty()) +
            ",\"defaultIndex\":" + (selected?.index ?: 0) + ",\"interfaces\":[" +
            interfaces.joinToString(",") { it.toJSON() } + "]}"
    }

    private fun PlatformNetworkRecord.toJSON(): String =
        "{\"name\":" + quote(name) + ",\"index\":" + index + ",\"mtu\":" + mtu +
            ",\"flags\":" + flags + ",\"addresses\":" + strings(addresses) +
            ",\"type\":" + quote(type) + ",\"dnsServers\":" + strings(dnsServers) +
            ",\"dnsSearchDomains\":" + strings(dnsSearchDomains) +
            ",\"gateways\":" + strings(gateways) + ",\"metered\":" + metered +
            ",\"vpn\":" + vpn + "}"

    private fun strings(values: List<String>): String =
        values.distinct().sorted().joinToString(",", "[", "]", transform = ::quote)

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u" + c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
