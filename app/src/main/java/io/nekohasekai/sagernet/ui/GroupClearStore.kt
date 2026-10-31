package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase

internal object GroupClearStore {
    fun clear(db: SagerDatabase, groupId: Long): List<ProxyEntity>? {
        var nodes: List<ProxyEntity>? = null
        db.runInTransaction {
            if (db.groupDao().getById(groupId) != null) {
                nodes = PendingProfileDeletes.loadGroup(groupId, db)
                db.proxyDao().deleteAll(groupId)
            }
        }
        return nodes
    }
    fun restore(db: SagerDatabase, groupId: Long, nodes: List<ProxyEntity>): Boolean {
        var restored = false
        db.runInTransaction {
            if (db.groupDao().getById(groupId) != null) {
                val existing = db.proxyDao().getIdsByGroup(groupId).toHashSet()
                val missing = nodes.filter { it.groupId == groupId && it.id !in existing }
                if (missing.isNotEmpty()) db.proxyDao().insert(missing)
                restored = true
            }
        }
        return restored
    }
}
