package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

/** No per-row event, nextOrder query, or transaction. Publish only after success. */
internal object ProfileBatchStore {
    fun append(db: SagerDatabase, groupId: Long, beans: List<AbstractBean>): Int {
        val nodes = beans.map { bean ->
            bean.applyDefaultValues()
            ProxyEntity(groupId = groupId).apply { putBean(bean) }
        }
        appendEntities(db, groupId, nodes)
        return nodes.size
    }

    fun appendEntities(db: SagerDatabase, groupId: Long, nodes: List<ProxyEntity>) {
        db.runInTransaction {
            var order = db.proxyDao().nextOrder(groupId) ?: 1L
            nodes.forEach { it.groupId = groupId; it.id = 0L; it.userOrder = order++ }
            nodes.chunked(512).forEach { db.proxyDao().insert(it) }
        }
    }
}
