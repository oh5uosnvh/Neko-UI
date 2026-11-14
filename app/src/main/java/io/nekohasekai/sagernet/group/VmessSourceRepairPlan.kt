package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

/** One plan is bound to the group/file pair inspected before confirmation. No networking. */
internal data class VmessSourceRepairPlan(val groupId: Long, val candidates: List<Candidate>) {
    data class Candidate(val profileId: Long, val expected: ByteArray, val repaired: VMessBean)

    fun commit(database: SagerDatabase, checkActive: () -> Unit = {}): Int = commitIds(database, checkActive).size

    fun commitIds(database: SagerDatabase, checkActive: () -> Unit = {}): Set<Long> {
        val written = linkedSetOf<Long>()
        database.runInTransaction {
            checkActive()
            candidates.forEach { candidate ->
                checkActive()
                if (LegacyVmessWsRecovery.commitIfUnchanged(database, candidate.profileId,
                        groupId, candidate.expected, candidate.repaired)) written += candidate.profileId
            }
        }
        return written
    }

    companion object {
        fun prepare(groupId: Long, profiles: List<ProxyEntity>, source: List<AbstractBean>): VmessSourceRepairPlan {
            val candidates = profiles.filter { it.groupId == groupId && it.type == ProxyEntity.TYPE_VMESS }
                .mapNotNull { profile ->
                    val bean = profile.vmessBean ?: return@mapNotNull null
                    val repaired = LegacyVmessWsRecovery.replacement(bean, source) ?: return@mapNotNull null
                    Candidate(profile.id, KryoConverters.serialize(bean), repaired)
                }
            return VmessSourceRepairPlan(groupId, candidates)
        }
    }
}
