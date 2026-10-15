package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase

internal object GroupClearStore {
    fun clear(db: SagerDatabase, groupId: Long): List<ProxyEntity>? {
        var nodes: List<ProxyEntity>? = null
        db.runInTransaction {
            if (db.groupDao().getById(groupId) != null) {
                nodes = db.proxyDao().getByGroup(groupId)
                db.proxyDao().deleteAll(groupId)
            }
        }
        return nodes
    }
    fun restore(db: SagerDatabase, groupId: Long, nodes: List<ProxyEntity>): Boolean {
        var restored = false
        db.runInTransaction {
            if (db.groupDao().getById(groupId) != null) {
                val missing = nodes.filter { it.groupId == groupId && db.proxyDao().getById(it.id) == null }
                if (missing.isNotEmpty()) db.proxyDao().insert(missing)
                restored = true
            }
        }
        return restored
    }
}
