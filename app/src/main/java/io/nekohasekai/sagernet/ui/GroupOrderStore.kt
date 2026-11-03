package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ID-only requests: never write the popup's (possibly stale) ProxyGroup objects. */
internal object GroupOrderStore {
    /** Deleted IDs are ignored; new/hidden groups follow in their current database order. */
    fun mergeOrder(currentIds: List<Long>, requestedIds: List<Long>): List<Long> {
        val remaining = currentIds.toMutableSet()
        val result = ArrayList<Long>(remaining.size)
        for (id in requestedIds) if (remaining.remove(id)) result.add(id)
        for (id in currentIds) if (remaining.remove(id)) result.add(id)
        return result
    }

    data class Saved(val groups: List<ProxyGroup>, val changed: List<ProxyGroup>)

    /** The read and updates share one transaction, so concurrent DNS/link edits cannot be lost. */
    fun save(db: SagerDatabase, requestedIds: List<Long>): Saved {
        val requested = requestedIds.toList()
        var saved = Saved(emptyList(), emptyList())
        db.runInTransaction {
            val dao = db.groupDao()
            val current = dao.allGroups().sortedWith(compareBy({ it.userOrder }, { it.id }))
            val byId = current.associateBy { it.id }
            val ordered = mergeOrder(current.map { it.id }, requested).map { byId.getValue(it) }
            val changed = ArrayList<ProxyGroup>()
            ordered.forEachIndexed { index, group ->
                val order = index + 1L
                if (group.userOrder != order) {
                    // This is a fresh row read under the transaction's write lock, NOT a UI copy.
                    group.userOrder = order
                    dao.updateOrder(group.id, order)
                    changed.add(group)
                }
            }
            saved = Saved(ordered, changed)
        }
        return saved
    }

    // Process lifetime, not popup lifetime: dismiss/reopen must not cancel or overtake a save.
    private val writer by lazy {
        GroupOrderWriter(
            write = { save(SagerDatabase.instance, it) },
            read = { SagerDatabase.groupDao.allGroups() },
            publish = {},
            publishSnapshot = { groups, changed ->
                var failure: Exception? = null
                GroupManager.iterator {
                    try {
                        groupsReordered(groups, changed)
                    } catch (e: Exception) {
                        // A detached listener must not stop delivery to the others.
                        failure = e
                        Logs.w(e)
                    }
                }
                failure?.let { throw it }
            },
            report = { Logs.w(it) },
        )
    }

    fun submit(ids: List<Long>, complete: (GroupOrderWriter.Result) -> Unit): Long =
        writer.submit(ids, complete)
}

/** FIFO actor: both commits AND main-thread notifications finish before the next request starts. */
internal class GroupOrderWriter(
    private val write: (List<Long>) -> GroupOrderStore.Saved,
    private val read: () -> List<ProxyGroup>,
    private val publish: suspend (List<ProxyGroup>) -> Unit,
    background: CoroutineDispatcher = Dispatchers.IO,
    private val delivery: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val report: (Exception) -> Unit = {},
    private val publishSnapshot: (suspend (List<ProxyGroup>, List<ProxyGroup>) -> Unit)? = null,
) {
    data class Result(
        val revision: Long,
        val groups: List<ProxyGroup>?,
        val error: Exception?,
    )

    private data class Request(
        val revision: Long,
        val ids: List<Long>,
        val complete: (Result) -> Unit,
    )

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private var revision = 0L

    init {
        CoroutineScope(SupervisorJob() + background).launch {
            for (request in requests) {
                var error: Exception? = null
                val saved = try {
                    write(request.ids)
                } catch (e: Exception) {
                    report(e)
                    error = e
                    null
                }
                val groups = saved?.groups ?: try {
                    read()
                } catch (e: Exception) {
                    report(e)
                    null
                }
                withContext(delivery) {
                    if (saved != null) {
                        try {
                            val snapshotPublisher = publishSnapshot
                            if (snapshotPublisher != null) snapshotPublisher(saved.groups, saved.changed)
                            else publish(saved.changed)
                        } catch (e: Exception) {
                            // The transaction succeeded; reconcile to it rather than pretending
                            // that a notification failure undid the database commit.
                            report(e)
                            error = e
                        }
                    }
                    try {
                        request.complete(Result(request.revision, groups, error))
                    } catch (e: Exception) {
                        report(e)
                    }
                }
            }
        }
    }

    @Synchronized
    fun submit(ids: List<Long>, complete: (Result) -> Unit): Long {
        val next = ++revision
        requests.trySend(Request(next, ids.toList(), complete)).getOrThrow()
        return next
    }

    /** Tests can drain the actor; production keeps the singleton alive for subsequent popups. */
    internal fun close() {
        requests.close()
    }
}
