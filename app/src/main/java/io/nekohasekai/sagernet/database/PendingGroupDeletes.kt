package io.nekohasekai.sagernet.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Delayed destructive deletion hides root groups everywhere while preserving entities/links for undo. */
object PendingGroupDeletes {
    private val removed = LinkedHashSet<Long>()
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges
    @Synchronized fun contains(id: Long) = id in removed
    @Synchronized fun ids(): Set<Long> = removed.toSet()
    @Synchronized fun mark(id: Long) { if (removed.add(id)) mutableChanges.value++ }
    @Synchronized fun unmark(ids: Collection<Long>) { if (removed.removeAll(ids.toSet())) mutableChanges.value++ }
}
