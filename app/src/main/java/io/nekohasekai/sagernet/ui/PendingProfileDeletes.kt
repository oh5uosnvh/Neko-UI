package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared logical deletion state. Database rows remain available until Undo expires. */
internal object PendingProfileDeletes {
    private val guard = Any()
    private val databaseAccess = Any()
    private val pending = LinkedHashMap<Long, Long>() // profile ID -> group ID
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun contains(id: Long): Boolean = synchronized(guard) { pending.containsKey(id) }

    fun stage(nodes: Collection<ProxyEntity>) = synchronized(guard) {
        nodes.forEach { pending[it.id] = it.groupId }
        revision.value += 1
    }

    fun undo(nodes: Collection<ProxyEntity>) = synchronized(guard) {
        nodes.forEach { pending.remove(it.id) }
        revision.value += 1
    }

    // Same source for the group page and the dropdown. Read/commit use the same
    // guard so physical deletion cannot briefly subtract the same node twice.
    fun count(groupId: Long, db: SagerDatabase = SagerDatabase.instance): Long = synchronized(databaseAccess) {
        val physical = db.proxyDao().countByGroup(groupId)
        synchronized(guard) { (physical - pending.values.count { it == groupId }).coerceAtLeast(0L) }
    }

    fun commit(nodes: List<ProxyEntity>, db: SagerDatabase = SagerDatabase.instance, clearSelection: Boolean = true) = synchronized(databaseAccess) {
        val ids = nodes.map { it.id }.distinct()
        db.runInTransaction {
            ids.chunked(400).forEach { db.proxyDao().deleteSnapshotIds(it) }
        }
        if (clearSelection && DataStore.selectedProxy in ids) DataStore.selectedProxy = 0L
        synchronized(guard) {
            ids.forEach { pending.remove(it) }
            revision.value += 1
        }
    }

    /** Bound each CursorWindow; sorting remains the same as the original group query. */
    fun loadGroup(groupId: Long, db: SagerDatabase = SagerDatabase.instance): List<ProxyEntity> {
        val nodes = ArrayList<ProxyEntity>()
        var after = 0L
        while (true) {
            val page = db.proxyDao().profilePageAfter(groupId, after, 128)
            if (page.isEmpty()) break
            nodes.addAll(page)
            after = page.last().id
            if (page.size < 128) break
        }
        return synchronized(guard) {
            nodes.filterNot { pending.containsKey(it.id) }
                .sortedWith(compareBy<ProxyEntity> { it.userOrder }.thenBy { it.id })
        }
    }
}
