package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.group.GroupDnsStore
import io.nekohasekai.sagernet.group.ImportGroupName
import io.nekohasekai.sagernet.group.NodeHostMapping
import io.nekohasekai.sagernet.group.SubscriptionDns

/**
 * A single transaction session for large imports. The old append() API remains
 * for small clipboard batches; StreamingProfileImport uses this to avoid
 * nesting 195 transactions and reloading GroupDnsStore for every 512 nodes.
 */
internal class ProfileBatchSession(
    private val db: SagerDatabase,
    private val groupId: Long,
    private val source: String,
    private val fileName: String,
) {
    private val group = db.groupDao().getById(groupId) ?: error("Import group no longer exists")
    private val settings = GroupDnsStore.prepare(group, db)
    private val configs = HashMap<String, SubscriptionDns.Config>()
    private val snapshots = LinkedHashSet<String>()
    private var order = db.proxyDao().nextOrder(groupId) ?: 1L
    private var wasEmpty = db.proxyDao().countByGroup(groupId) == 0L
    private val initiallyEmpty = wasEmpty

    fun append(beans: List<AbstractBean>) {
        if (beans.isEmpty()) return
        val nodes = ArrayList<ProxyEntity>(beans.size)
        for (bean in beans) {
            bean.applyDefaultValues()
            if (source.isNotBlank()) bean.subscriptionDnsSource = source
            bean.subscriptionDns.orEmpty().takeIf { it.isNotBlank() }?.let(snapshots::add)
            val config = configs.getOrPut(bean.subscriptionDns) { GroupDnsStore.config(bean, settings) }
            NodeHostMapping.apply(bean, config.hosts, settings.applyHosts != false)
            nodes += ProxyEntity(groupId = groupId, userOrder = order++).apply { putBean(bean) }
        }
        nodes.chunked(512).forEach { db.proxyDao().insert(it) }
        wasEmpty = false
    }

    fun finish(): Int {
        if (snapshots.isNotEmpty()) {
            settings.importedDnsConfig = GroupDnsStore.mergeSnapshots(
                listOf(settings.importedDnsConfig.orEmpty()) + snapshots
            )
            settings.importedDnsSource = source.ifBlank { "file" }
        }
        if (initiallyEmpty && source == "file") {
            ImportGroupName.fromFileName(fileName).takeIf { it.isNotBlank() }?.let { group.name = it }
        }
        db.groupDao().updateGroup(group)
        return snapshots.size
    }
}
