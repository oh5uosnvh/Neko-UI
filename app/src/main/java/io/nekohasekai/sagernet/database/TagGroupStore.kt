package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Callable

/** Synchronous database API. UI callers must dispatch reads AND writes to IO. */
object TagGroupStore {
    /** A single batch. Failed restoration is retryable; successful restoration is idempotent. */
    class UndoRecord internal constructor(private val action: () -> Unit) {
        private var restored = false

        @Synchronized
        fun restore() {
            if (restored) return
            action()
            restored = true
        }
    }
    private val store get() = SagerDatabase.instance.tagStore
    val changes: StateFlow<Long> get() = store.changes
    fun tags(): List<TagGroup> = store.tags()
    fun members(tagId: Long): Set<Long> = store.members(tagId)
    fun get(tagId: Long): TagGroup? = store.get(tagId)
    fun create(name: String): TagGroup = store.create(name)
    fun rename(id: Long, name: String) = store.rename(id, name)
    fun reorder(ids: List<Long>) = store.reorder(ids)
    fun add(tagId: Long, ids: Collection<Long>): UndoRecord? = store.add(tagId, ids)
    fun remove(tagId: Long, ids: Collection<Long>): UndoRecord? = store.remove(tagId, ids)
    fun delete(tagId: Long): UndoRecord? = store.delete(tagId)

    /** Same store/change stream for all clients of an injected Room database. */
    fun forDatabase(db: SagerDatabase): Store = db.tagStore

    class Store internal constructor(private val db: SagerDatabase) {
        private val tags get() = db.tagGroupDao()
        private val memberships get() = db.tagMembershipDao()
        private val mutableChanges = MutableStateFlow(0L)
        val changes: StateFlow<Long> = mutableChanges.asStateFlow()
        private val notificationLock = Any()

        private fun <T> transaction(block: () -> T): T {
            check(!db.inTransaction()) {
                "TagGroupStore 必须拥有最外层事务；导入请使用 GroupManager.createGroup(group, tagId, profiles)"
            }
            return db.runInTransaction(Callable { block() })
        }
        private fun changed() = synchronized(notificationLock) {
            mutableChanges.value = mutableChanges.value + 1
        }

        fun tags(): List<TagGroup> = tags.all()
        fun members(tagId: Long): Set<Long> = memberships.members(tagId).toSet()
        fun get(tagId: Long): TagGroup? = tags.get(tagId)

        private fun normalizedName(value: String, exceptId: Long = 0): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "标签组名称不能为空" }
            require(tags.nameCount(trimmed, exceptId) == 0) { "标签组名称已存在" }
            return trimmed
        }

        private fun requireTag(id: Long): TagGroup =
            requireNotNull(tags.get(id)) { "标签组不存在: $id" }

        private fun requireGroups(ids: Collection<Long>) {
            ids.forEach { requireNotNull(db.groupDao().getById(it)) { "配置分组不存在: $it" } }
        }

        fun create(name: String): TagGroup {
            val result = transaction {
                val order = tags.maxOrder() ?: 0L
                check(order < Long.MAX_VALUE) { "标签组排序已满" }
                val tag = TagGroup(name = normalizedName(name), userOrder = order + 1)
                tag.copy(id = tags.insert(tag))
            }
            changed()
            return result
        }

        fun rename(id: Long, name: String) {
            val didChange = transaction {
                val original = requireTag(id)
                val normalized = name.trim()
                require(normalized.isNotEmpty()) { "标签组名称不能为空" }
                if (original.name == normalized) false else {
                    normalizedName(normalized, id)
                    tags.rename(id, normalized)
                    true
                }
            }
            if (didChange) changed()
        }

        /** Stale drag snapshots: deleted IDs are ignored; newly created tags stay at the tail. */
        fun reorder(ids: List<Long>) {
            val requested = ids.distinct()
            val didChange = transaction {
                val current = tags.all()
                val byId = current.associateBy { it.id }
                val order = requested.filter { it in byId } + current.map { it.id }.filter { it !in requested }
                var any = false
                order.forEachIndexed { index, id ->
                    val next = index.toLong() + 1
                    if (byId.getValue(id).userOrder != next) {
                        tags.reorder(id, next)
                        any = true
                    }
                }
                any
            }
            if (didChange) changed()
        }

        fun add(tagId: Long, ids: Collection<Long>): UndoRecord? {
            val requested = ids.toSet()
            val inserted = transaction {
                requireTag(tagId)
                requireGroups(requested)
                val added = requested - memberships.members(tagId).toSet()
                added.forEach { memberships.insert(TagMembership(tagId, it)) }
                added
            }
            if (inserted.isEmpty()) return null
            changed()
            return UndoRecord {
                val didChange = transaction {
                    var any = false
                    inserted.forEach { if (memberships.delete(tagId, it) > 0) any = true }
                    any
                }
                if (didChange) changed()
            }
        }

        fun remove(tagId: Long, ids: Collection<Long>): UndoRecord? {
            val requested = ids.toSet()
            val removed = transaction {
                requireTag(tagId)
                requireGroups(requested)
                val removed = requested intersect memberships.members(tagId).toSet()
                removed.forEach { memberships.delete(tagId, it) }
                removed.map { TagMembership(tagId, it) }
            }
            if (removed.isEmpty()) return null
            changed()
            return UndoRecord { restoreRelations(removed) }
        }

        fun delete(tagId: Long): UndoRecord? {
            val snapshot = transaction {
                val tag = tags.get(tagId) ?: return@transaction null
                val relations = memberships.members(tagId).map { TagMembership(tagId, it) }
                tags.delete(tagId) // FK cascade affects references only, never root groups/profiles.
                tag to relations
            } ?: return null
            changed()
            return UndoRecord {
                transaction {
                    check(tags.get(snapshot.first.id) == null) { "标签组 ID 已被占用，不能覆盖现有标签组" }
                    requireGroups(snapshot.second.map { it.groupId })
                    // ABORT, never REPLACE. A new same-name tag is an independent entity.
                    tags.insert(snapshot.first)
                    snapshot.second.forEach { memberships.insert(it) }
                }
                changed()
            }
        }

        private fun restoreRelations(relations: Collection<TagMembership>) {
            val snapshot = relations.toSet()
            val didChange = transaction {
                snapshot.map { it.tagId }.toSet().forEach { requireTag(it) }
                requireGroups(snapshot.map { it.groupId }.toSet())
                var any = false
                val existing = snapshot.map { it.tagId }.toSet().associateWith { memberships.members(it).toSet() }
                snapshot.forEach {
                    if (it.groupId !in existing.getValue(it.tagId)) {
                        memberships.insert(it)
                        any = true
                    }
                }
                any
            }
            if (didChange) changed()
        }

        /** GroupManager uses this helper so root insertion + classification share ONE commit. */
        internal fun createRootGroup(
            group: ProxyGroup,
            tagId: Long?,
            profiles: List<ProxyEntity> = emptyList(),
        ): ProxyGroup {
            val committed = transaction {
                if (tagId != null) requireTag(tagId)
                val candidate = group.copy(userOrder = db.groupDao().nextOrder() ?: 1).applyDefaultValues()
                val id = db.groupDao().createGroup(candidate)
                if (tagId != null) memberships.insert(TagMembership(tagId, id))
                if (profiles.isNotEmpty()) {
                    db.proxyDao().insert(profiles.map { it.copy(id = 0, groupId = id) })
                }
                candidate.copy(id = id)
            }
            // Do not leave the caller's mutable object with an uncommitted ID on failure.
            group.id = committed.id
            group.userOrder = committed.userOrder
            changed()
            return group
        }

        /** Both global-delete overloads share the same atomic cascade/profile-cleanup path. */
        internal fun deleteRootGroups(ids: Collection<Long>) {
            val requested = ids.toSet()
            val didChange = transaction {
                var any = false
                requested.forEach { id ->
                    db.proxyDao().deleteByGroup(id)
                    if (db.groupDao().deleteById(id) > 0) any = true
                }
                any
            }
            if (didChange) changed()
        }
    }
}
