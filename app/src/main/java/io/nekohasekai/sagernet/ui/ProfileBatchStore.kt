package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.group.GroupDnsStore
import io.nekohasekai.sagernet.group.ImportGroupName
import io.nekohasekai.sagernet.group.NodeHostMapping

/** No per-row event, nextOrder query, or transaction. Publish only after success. */
internal object ProfileBatchStore {
    fun append(db: SagerDatabase, groupId: Long, beans: List<AbstractBean>, source: String = "", fileName: String = ""): Int {
        db.runInTransaction {
            val group = db.groupDao().getById(groupId) ?: error("Group no longer exists")
            val wasEmpty = db.proxyDao().countByGroup(groupId) == 0L
            val settings = GroupDnsStore.prepare(group, db)
            val snapshots = beans.map { it.subscriptionDns.orEmpty() }.filter { it.isNotBlank() }.distinct()
            if (snapshots.isNotEmpty()) {
                settings.importedDnsConfig = GroupDnsStore.mergeSnapshots(listOf(settings.importedDnsConfig.orEmpty()) + snapshots)
                settings.importedDnsSource = source.ifBlank { "file" }
            }
            if (wasEmpty && source == "file") {
                ImportGroupName.fromFileName(fileName).takeIf { it.isNotBlank() }?.let { group.name = it }
            }
            val configs = hashMapOf<String, io.nekohasekai.sagernet.group.SubscriptionDns.Config>()
            val nodes = beans.map { bean ->
                bean.applyDefaultValues()
                if (source.isNotBlank()) bean.subscriptionDnsSource = source
                val config = configs.getOrPut(bean.subscriptionDns) { GroupDnsStore.config(bean, settings) }
                NodeHostMapping.apply(bean, config.hosts, settings.applyHosts != false)
                ProxyEntity(groupId = groupId).apply { putBean(bean) }
            }
            appendEntities(db, groupId, nodes)
            db.groupDao().updateGroup(group)
        }
        return beans.size
    }

    fun appendEntities(db: SagerDatabase, groupId: Long, nodes: List<ProxyEntity>) {
        db.runInTransaction {
            var order = db.proxyDao().nextOrder(groupId) ?: 1L
            nodes.forEach { it.groupId = groupId; it.id = 0L; it.userOrder = order++ }
            nodes.chunked(512).forEach { db.proxyDao().insert(it) }
        }
    }
}
