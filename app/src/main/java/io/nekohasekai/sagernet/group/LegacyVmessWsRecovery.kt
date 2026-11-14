package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

/** Repairs only pre22's loss of a WS path, never guessed defaults or unrelated node settings. */
internal object LegacyVmessWsRecovery {
    fun eligible(bean: VMessBean): Boolean = !bean.isVLESS && bean.type == "ws" &&
        bean.path.isNullOrBlank() && bean.customOutboundJson.isNullOrBlank() && bean.customConfigJson.isNullOrBlank()

    fun replacement(old: VMessBean, source: List<AbstractBean>): VMessBean? {
        if (!eligible(old)) return null
        fun identity(bean: VMessBean): String = bean.mappedOriginalAddress
            .takeUnless { it.isNullOrBlank() }?.trim()?.lowercase()
            ?: bean.serverAddress.orEmpty().trim().lowercase()
        val originalAddress = identity(old)
        if (originalAddress.isBlank()) return null
        val candidates = source.filterIsInstance<VMessBean>().filter { candidate ->
            !candidate.isVLESS && candidate.type == "ws" && candidate.path.isNotBlank() &&
                candidate.uuid == old.uuid && candidate.serverPort == old.serverPort &&
                candidate.alterId == old.alterId && candidate.security == old.security &&
                candidate.host.equals(old.host, true) &&
                identity(candidate) == originalAddress
        }
        val named = candidates.filter { it.name == old.name }
        val sourceBean = (if (named.isNotEmpty()) named else candidates).singleOrNull() ?: return null
        // Clone preserves credentials, DNS, hosts mapping, mux and every user setting.
        return old.clone().apply { path = sourceBean.path }
    }

    /** Caller-owned transaction/confirmation; compare bytes before replacing only the Bean. */
    fun commitIfUnchanged(database: io.nekohasekai.sagernet.database.SagerDatabase,
        profileId: Long, groupId: Long, expected: ByteArray, repaired: VMessBean): Boolean {
        val current = database.proxyDao().getById(profileId) ?: return false
        if (current.groupId != groupId || current.type != io.nekohasekai.sagernet.database.ProxyEntity.TYPE_VMESS ||
            !io.nekohasekai.sagernet.fmt.KryoConverters.serialize(current.vmessBean).contentEquals(expected)) return false
        return database.proxyDao().repairLegacyVmessPath(profileId, groupId,
            io.nekohasekai.sagernet.fmt.KryoConverters.serialize(repaired)) == 1
    }
}
