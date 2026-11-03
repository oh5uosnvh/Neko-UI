package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.GroupOrderStore
import io.nekohasekai.sagernet.ui.GroupOrderWriter
import io.nekohasekai.sagernet.ui.GroupPageRequests
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class GroupOrderPagerStressTest {
    private fun appFile(relative: String): File {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
        return File(app, relative.removePrefix("app/"))
    }

    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun hundredGestureSnapshotsHaveOnePublicationAndReceiptEachInFifo() {
        val queue = Queue()
        val names = (1L..500L).associateWith { "组${it}-" + "大名称".repeat(200) }
        var stored = names.keys.mapIndexed { i, id -> ProxyGroup(id = id, userOrder = i + 1L, name = names.getValue(id)) }
        val events = ArrayList<String>()
        var publications = 0
        val writer = GroupOrderWriter(
            write = { ids ->
                events.add("write")
                val rows = stored.associateBy { it.id }
                stored = GroupOrderStore.mergeOrder(stored.map { it.id }, ids).mapIndexed { i, id ->
                    rows.getValue(id).copy(userOrder = i + 1L)
                }
                GroupOrderStore.Saved(stored, stored)
            }, read = { error("no recovery query after success") },
            publish = { error("must use batch, not legacy per-row publication") },
            publishSnapshot = { snapshot, changed ->
                assertEquals(500, snapshot.size)
                assertEquals(500, changed.size)
                publications++
                events.add("publish")
            }, background = queue, delivery = queue,
        )
        val order = names.keys.toMutableList()
        repeat(100) { i ->
            order.add((i * 13) % order.size, order.removeAt(order.lastIndex))
            writer.submit(order) { result ->
                assertNull(result.error)
                assertEquals(i + 1L, result.revision)
                events.add("complete")
            }
        }
        val latest = order.toList()
        order.reverse() // queue owns immutable snapshots
        writer.close()
        queue.drain()
        assertEquals(100, publications)
        assertEquals(List(100) { listOf("write", "publish", "complete") }.flatten(), events)
        assertEquals(latest, stored.map { it.id })
        assertEquals(names, stored.associate { it.id to it.name })
    }

    @Test fun legacyListenerGetsChangedRowsButBatchUiGetsOneCompleteSnapshot() = runBlocking {
        val rows = (1L..500L).map { ProxyGroup(id = it, userOrder = 501L - it) }
        var legacyCalls = 0
        var batchCalls = 0
        val legacy = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) { legacyCalls++ }
        }
        val batch = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) { error("per-row UI echo") }
            override suspend fun groupsReordered(groups: List<ProxyGroup>, changed: List<ProxyGroup>) {
                batchCalls++
                assertEquals(500, groups.size)
                assertEquals(2, changed.size)
            }
        }
        legacy.groupsReordered(rows, rows.take(2))
        batch.groupsReordered(rows, rows.take(2))
        assertEquals(2, legacyCalls)
        assertEquals(1, batchCalls)
    }

    @Test fun rapidClicksDoNotInterruptAnimationAndLatestStableTargetWins() {
        val requests = GroupPageRequests()
        requests.request(2)
        assertEquals(2L, requests.takeWhenIdle(true))
        repeat(100) {
            requests.request(it + 3L)
            assertNull(requests.takeWhenIdle(false))
        }
        assertEquals(102L, requests.takeWhenIdle(true))
        assertNull(requests.takeWhenIdle(true))
        requests.request(99)
        requests.clear()
        assertNull(requests.takeWhenIdle(true))
    }

    @Test fun sourceHotpathsKeepOrderEchoAndResumeFreeOfNodeReloadAndFocusSteal() {
        val source = appFile("app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val page = source.substringAfter("class GroupFragment : Fragment()")
        val resume = page.substringAfter("override fun onResume() {").substringBefore("fun checkOrderMenu()")
        assertFalse(resume.contains("configurationListView.size"))
        assertFalse(resume.contains("requestFocus()"))
        assertTrue(resume.contains("isProfileLoadInFlight()"))
        val batch = page.substringAfter("override suspend fun groupsReordered").substringBefore("override suspend fun groupUpdated")
        assertTrue(batch.contains("userOrder"))
        assertFalse(batch.contains("reloadProfiles()"))
        assertFalse(batch.contains("SagerDatabase"))
        val pager = source.substringAfter("inner class GroupPagerAdapter").substringBefore("class GroupFragment : Fragment()")
        val pagerBatch = pager.substringAfter("override suspend fun groupsReordered").substringBefore("override suspend fun groupUpdated")
        assertEquals(1, Regex("notifyDataSetChanged\\(\\)").findAll(pagerBatch).count())
        assertFalse(pagerBatch.contains("notifyItemMoved("))
        assertTrue(source.contains("pageRequests.applyWhenIdle(groupPager"))
        assertTrue(appFile("app/src/main/java/io/nekohasekai/sagernet/ui/GroupPageRequests.kt").readText().contains("pager.setCurrentItem(position, true)"))
        val popup = appFile("app/src/main/java/io/nekohasekai/sagernet/ui/TopBarController.kt").readText()
        val popupBatch = popup.substringAfter("override suspend fun groupsReordered").substringBefore("override suspend fun groupAdd")
        assertFalse(popupBatch.contains("requestRefresh()"))
        assertFalse(popupBatch.contains("loadCounts()"))
        val accept = popup.substringAfter("private fun accept(").substringBefore("private fun onSettled()")
        assertFalse(accept.contains("loadCounts()"))
        assertFalse(popup.contains("ids.associateWith { PendingProfileDeletes.count(it) }"))
        assertTrue(appFile("app/src/main/java/io/nekohasekai/sagernet/ui/PendingProfileDeletes.kt").readText().contains("GROUP BY groupId"))
        assertTrue(appFile("app/src/main/java/io/nekohasekai/sagernet/database/ProxyGroup.kt").readText().contains("UPDATE proxy_groups SET userOrder = :order WHERE id = :groupId"))
    }
}
