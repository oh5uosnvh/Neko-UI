package io.nekohasekai.sagernet.ui

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

/** Backup wire projection only. Never change Room/Kryo's internal serialization versions. */
internal object BackupCompatibility {
    fun isTagSetting(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return normalized.startsWith("tag") ||
            listOf("grouptag", "selectedtag", "currenttag").any(normalized::startsWith)
    }

    fun hasGroupExtensions(value: ProxyGroup): Boolean = value.isSelector || value.frontProxy != -1L ||
        value.landingProxy != -1L || (value.type != GroupType.SUBSCRIPTION && value.subscription != null) ||
        value.subscription?.let { s ->
            (s.filterMode ?: 0) != 0 || !s.filterRegex.isNullOrBlank() ||
                listOf(s.serverDnsResolver, s.directDnsResolver, s.privateDnsConfig, s.hostsConfig,
                    s.importedDnsConfig).any { !it.isNullOrBlank() }
        } == true

    fun encode(value: Parcelable): String = withParcel { parcel ->
        when (value) {
            is ProxyGroup -> parcel.writeByteArray(buffer { group(value, it) })
            is ProxyEntity -> parcel.writeByteArray(buffer { profile(value, it) })
            is RuleEntity -> writeRule(value, parcel)
            else -> value.writeToParcel(parcel, 0)
        }
        Util.b64EncodeUrlSafe(parcel.marshall())
    }

    fun <T> decode(value: String, creator: Parcelable.Creator<T>): T = withParcel { parcel ->
        val bytes = Util.b64Decode(value)
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        creator.createFromParcel(parcel)
    }

    private inline fun <T> withParcel(block: (Parcel) -> T): T {
        val parcel = Parcel.obtain()
        return try { block(parcel) } finally { parcel.recycle() }
    }

    private fun buffer(write: (ByteBufferOutput) -> Unit): ByteArray =
        ByteBufferOutput(512, -1).use { output -> write(output); output.toBytes() }

    private fun unsupported(ok: Boolean, field: String) {
        require(ok) { "Original NekoBox backup cannot represent $field; export cancelled (no data omitted)." }
    }

    /** Subscription is INLINE, not length-delimited: v5 would shift the parent's order field. */
    internal fun group(value: ProxyGroup, output: ByteBufferOutput) {
        val sub = value.subscription
        // App-only selector/front/landing and local DNS fields have no upstream wire slot.
        // The caller warns about this legacy projection; never mutate the live group.
        output.writeInt(0)
        output.writeLong(value.id)
        output.writeLong(value.userOrder)
        output.writeBoolean(value.ungrouped)
        output.writeString(value.name)
        output.writeInt(value.type)
        if (value.type == GroupType.SUBSCRIPTION) {
            val s = requireNotNull(sub) { "Subscription group has no subscription" }
            output.writeInt(1)
            output.writeInt(s.type ?: 0)
            output.writeString(s.link ?: "")
            output.writeBoolean(s.forceResolve == true)
            output.writeBoolean(s.deduplication == true)
            output.writeBoolean(s.updateWhenConnectedOnly == true)
            output.writeString(s.customUserAgent ?: "")
            output.writeBoolean(s.autoUpdate == true)
            output.writeInt(s.autoUpdateDelay ?: 1440)
            output.writeInt(s.lastUpdated ?: 0)
            output.writeString(s.subscriptionUserinfo)
        }
        output.writeInt(value.order)
    }

    internal fun profile(value: ProxyEntity, output: ByteBufferOutput) {
        unsupported(value.type !in setOf(ProxyEntity.TYPE_SSR, ProxyEntity.TYPE_JUICITY,
            ProxyEntity.TYPE_SNELL, ProxyEntity.TYPE_XHTTP, ProxyEntity.TYPE_OPPA),
            "profile type ${value.type} (not supported by official 1.4.2)")
        output.writeInt(0)
        output.writeLong(value.id)
        output.writeLong(value.groupId)
        output.writeInt(value.type)
        output.writeLong(value.userOrder)
        output.writeLong(value.tx)
        output.writeLong(value.rx)
        output.writeInt(value.status)
        output.writeInt(value.ping)
        output.writeString(value.uuid)
        output.writeString(value.error)
        val bytes = buffer { bean(value.requireBean(), it) }
        output.writeVarInt(bytes.size, true)
        output.writeBytes(bytes)
        output.writeBoolean(value.dirty)
    }

    internal fun bean(value: AbstractBean, output: ByteBufferOutput) {
        when (value) {
            is TrojanBean -> {
                unsupported(value.mpw.isNullOrBlank(), "Trojan mpw")
                output.writeInt(2)
                standard(value, output)
                output.writeString(value.password)
            }
            is StandardV2RayBean -> standard(value, output)
            is ShadowsocksBean -> {
                unsupported(value.enableMux != true, "Shadowsocks multiplexing")
                output.writeInt(2)
                output.writeString(value.serverAddress)
                output.writeInt(value.serverPort)
                output.writeString(value.method)
                output.writeString(value.password)
                output.writeString(value.plugin)
                output.writeBoolean(value.sUoT == true)
            }
            is AnyTLSBean -> {
                unsupported(value.realityPubKey.isNullOrBlank() && value.realityShortId.isNullOrBlank() &&
                    value.minIdleSession == null && value.idleSessionCheckInterval == null &&
                    value.idleSessionTimeout == null, "AnyTLS REALITY/session pool options")
                output.writeInt(0)
                output.writeString(value.serverAddress)
                output.writeInt(value.serverPort)
                output.writeString(value.password)
                output.writeString(value.sni)
                output.writeString(value.alpn)
                output.writeString(value.certificates)
                output.writeString(value.utlsFingerprint)
                output.writeBoolean(value.allowInsecure == true)
                output.writeString(value.echConfig)
            }
            else -> {
                // These protocol serializers match official 1.4.2 byte-for-byte.
                value.serialize(output)
            }
        }
        // Keep the inherited extra tail: upstream reads the original three fields and ignores
        // the remaining bytes inside this LENGTH-DELIMITED bean. No tag data lives here.
        output.writeInt(3)
        output.writeString(value.name)
        output.writeString(outboundWithWsHeaders(value))
        output.writeString(value.customConfigJson)
        output.writeString(value.subscriptionDns)
        output.writeString(value.subscriptionDnsSource)
        output.writeString(value.mappedOriginalAddress)
        output.writeString(value.mappedTargetAddress)
        output.writeString(value.mappedIdentityFields)
    }

    private fun standard(value: StandardV2RayBean, output: ByteBufferOutput) {
        unsupported(value.type in setOf("tcp", "ws", "http", "grpc"), "V2Ray transport ${value.type}")
        unsupported(value.vlessEncryption.isNullOrBlank() || value.vlessEncryption == "none",
            "VLESS encryption")
        unsupported(value.enableMux != true || ((value.muxMode ?: 0) == 0 && value.muxBrutal != true),
            "V2Ray connection-mode/Brutal multiplexing")
        output.writeInt(4)
        output.writeString(value.serverAddress)
        output.writeInt(value.serverPort)
        output.writeString(value.uuid)
        output.writeString(value.encryption)
        if (value is VMessBean) output.writeInt(value.alterId)
        output.writeString(value.type)
        when (value.type) {
            "ws" -> {
                output.writeString(value.host)
                output.writeString(value.path)
                output.writeInt(value.wsMaxEarlyData)
                output.writeString(value.earlyDataHeaderName)
            }
            "http" -> { output.writeString(value.host); output.writeString(value.path) }
            "grpc" -> output.writeString(value.path)
        }
        output.writeString(value.security)
        if (value.security == "tls") {
            output.writeString(value.sni)
            output.writeString(value.alpn)
            output.writeString(value.certificates)
            output.writeBoolean(value.allowInsecure == true)
            output.writeString(value.utlsFingerprint)
            output.writeString(value.realityPubKey)
            output.writeString(value.realityShortId)
        }
        output.writeBoolean(value.enableECH == true)
        output.writeString(value.echConfig)
        output.writeInt(value.packetEncoding)
        output.writeBoolean(value.enableMux == true)
        output.writeBoolean(value.muxPadding == true)
        output.writeInt(value.muxType)
        output.writeInt(value.muxConcurrency)
    }

    private fun outboundWithWsHeaders(value: AbstractBean): String? {
        if (value !is StandardV2RayBean || value.type != "ws" || value.wsExtraHeaders.isNullOrBlank())
            return value.customOutboundJson
        val root = if (value.customOutboundJson.isNullOrBlank()) JSONObject() else JSONObject(value.customOutboundJson)
        val transport = root.optJSONObject("transport") ?: JSONObject().also { root.put("transport", it) }
        val extra = JSONObject(value.wsExtraHeaders)
        val headers = JSONObject()
        extra.keys().forEach { key ->
            // Same precedence as V2RayFmt: Host comes from the dedicated host field.
            if (!key.equals("Host", true)) headers.put(key, extra.getString(key))
        }
        // Existing custom outbound is applied last by ConfigBuilder; preserve that precedence.
        transport.optJSONObject("headers")?.let { custom ->
            custom.keys().forEach { key -> headers.put(key, custom.get(key)) }
        }
        transport.put("headers", headers)
        return root.toString()
    }

    /** Official and starifly added unversioned Parcel constructor fields in different places. */
    fun decodeRule(value: String): RuleEntity = withParcel { parcel ->
        val bytes = Util.b64Decode(value)
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        val rule = RuleEntity(id = parcel.readLong(), name = parcel.readString().orEmpty(),
            config = parcel.readString().orEmpty(), userOrder = parcel.readLong(),
            enabled = parcel.readInt() != 0, domains = parcel.readString().orEmpty(),
            ip = parcel.readString().orEmpty(), port = parcel.readString().orEmpty(),
            sourcePort = parcel.readString().orEmpty(), network = parcel.readString().orEmpty(),
            source = parcel.readString().orEmpty(), protocol = parcel.readString().orEmpty())
        val tail = parcel.dataPosition()
        fun readTail(): Boolean = try {
            rule.outbound = parcel.readLong()
            val size = parcel.readInt()
            require(size >= 0 && size <= parcel.dataAvail() / 4)
            rule.packages = (0 until size).map { requireNotNull(parcel.readString()) }.toSet()
            parcel.dataAvail() == 0
        } catch (_: RuntimeException) { false }
        if (!readTail()) {
            parcel.setDataPosition(tail)
            rule.ruleset = parcel.readString().orEmpty()
            if (!readTail()) {
                // Our pre22 layout: ruleset plus builtin/subscription bookkeeping.
                parcel.setDataPosition(tail)
                rule.ruleset = parcel.readString().orEmpty()
                rule.builtinKey = parcel.readString().orEmpty()
                rule.sourceUrl = parcel.readString().orEmpty()
                rule.subscriptionState = parcel.readString().orEmpty()
                rule.lastUpdateError = parcel.readString().orEmpty()
                rule.cachedRuleCount = parcel.readInt()
                rule.skippedRuleCount = parcel.readInt()
                require(readTail()) { "Invalid route backup Parcel" }
            }
        }
        rule
    }

    // RuleEntity is unversioned @Parcelize. Never export our extra constructor fields.
    private fun writeRule(value: RuleEntity, parcel: Parcel) {
        unsupported(value.ruleset.isBlank(), "route rule sets (official/third-party Parcel layouts differ)")
        parcel.writeLong(value.id)
        parcel.writeString(value.name)
        parcel.writeString(value.config)
        parcel.writeLong(value.userOrder)
        parcel.writeInt(if (value.enabled) 1 else 0)
        listOf(value.domains, value.ip, value.port, value.sourcePort, value.network, value.source,
            value.protocol).forEach(parcel::writeString)
        parcel.writeLong(value.outbound)
        parcel.writeInt(value.packages.size)
        value.packages.forEach(parcel::writeString)
    }
}
