package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.GroupOrderStore
import io.nekohasekai.sagernet.ui.GroupOrderWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class GroupOrderStoreTest {
    @Test fun mergeIgnoresDeletedAndDuplicateIdsAndAppendsNewGroups() {
        assertEquals(listOf(3L, 1L, 4L, 5L), GroupOrderStore.mergeOrder(
            listOf(1L, 3L, 4L, 5L), listOf(3L, 2L, 3L, 1L, 99L),
        ))
        assertEquals(emptyList<Long>(), GroupOrderStore.mergeOrder(emptyList(), listOf(4L)))
        assertEquals(listOf(2L, 1L), GroupOrderStore.mergeOrder(listOf(2L, 1L, 2L), emptyList()))
    }

    @Test fun mergeIsIdempotentAndAlwaysContainsExactlyTheCurrentStableIds() {
        val random = Random(203)
        repeat(300) {
            val current = (1L..50L).shuffled(random).take(random.nextInt(51))
            val requested = (1L..60L).shuffled(random).take(random.nextInt(61))
            val merged = GroupOrderStore.mergeOrder(current, requested + requested)
            assertEquals(current.toSet(), merged.toSet())
            assertEquals(merged.size, merged.distinct().size)
            assertEquals(merged, GroupOrderStore.mergeOrder(current, merged))
            assertEquals(requested.filter { it in current }, merged.take(requested.count { it in current }))
            assertEquals(current.filter { it !in requested }, merged.filter { it !in requested })
        }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun groups(ids: List<Long>) = ids.mapIndexed { index, id ->
        ProxyGroup(id = id, userOrder = index + 1L, name = "Group $id")
    }

    @Test fun rapidRequestsCopySnapshotsAndCommitNotifyCompleteInFifoOrder() {
        val dispatcher = QueueDispatcher()
        val events = ArrayList<String>()
        var persisted = groups(listOf(1L, 2L, 3L))
        val writer = GroupOrderWriter(
            write = { ids ->
                events.add("write:$ids")
                persisted = groups(ids)
                GroupOrderStore.Saved(persisted, persisted)
            },
            read = { persisted },
            publish = { events.add("publish:${it.map { g -> g.id }}") },
            background = dispatcher, delivery = dispatcher,
        )
        val first = mutableListOf(3L, 1L, 2L)
        writer.submit(first) { events.add("complete:${it.revision}") }
        first.reverse() // Mutating the caller's list must not mutate an enqueued request.
        writer.submit(listOf(2L, 3L, 1L)) { events.add("complete:${it.revision}") }
        writer.close() // Closing a popup/queue cannot discard already accepted writes.
        dispatcher.drain()
        assertEquals(listOf(2L, 3L, 1L), persisted.map { it.id })
        assertEquals(listOf(
            "write:[3, 1, 2]", "publish:[3, 1, 2]", "complete:1",
            "write:[2, 3, 1]", "publish:[2, 3, 1]", "complete:2",
        ), events)
    }

    @Test fun failedAndCancelledWritesReturnSavedOrderWithoutStoppingLaterRequests() {
        for (failure in listOf(IllegalStateException("disk full"), CancellationException("cancelled"))) {
            val dispatcher = QueueDispatcher()
            var persisted = groups(listOf(1L, 2L, 3L))
            val results = ArrayList<GroupOrderWriter.Result>()
            var attempts = 0
            var broadcasts = 0
            val writer = GroupOrderWriter(
                write = { ids ->
                    if (attempts++ == 0) throw failure
                    persisted = groups(ids)
                    GroupOrderStore.Saved(persisted, persisted)
                },
                read = { persisted }, publish = { broadcasts++ },
                background = dispatcher, delivery = dispatcher,
            )
            writer.submit(listOf(3L, 1L, 2L)) { results.add(it) }
            writer.submit(listOf(2L, 1L, 3L)) { results.add(it) }
            writer.close()
            dispatcher.drain()
            assertSame(failure, results[0].error)
            assertEquals(listOf(1L, 2L, 3L), results[0].groups!!.map { it.id })
            assertNull(results[1].error)
            assertEquals(listOf(2L, 1L, 3L), persisted.map { it.id })
            assertEquals(1, broadcasts)
        }
    }

    @Test fun listenerFailureDoesNotPretendToRollbackACommittedTransaction() {
        val dispatcher = QueueDispatcher()
        val committed = groups(listOf(2L, 1L))
        var result: GroupOrderWriter.Result? = null
        val writer = GroupOrderWriter(
            write = { GroupOrderStore.Saved(committed, committed) },
            read = { error("No rollback read after a successful commit") },
            publish = { throw IllegalStateException("detached listener") },
            background = dispatcher, delivery = dispatcher,
        )
        writer.submit(listOf(2L, 1L)) { result = it }
        writer.close()
        dispatcher.drain()
        assertNotNull(result!!.error)
        assertEquals(listOf(2L, 1L), result!!.groups!!.map { it.id })
    }
}
