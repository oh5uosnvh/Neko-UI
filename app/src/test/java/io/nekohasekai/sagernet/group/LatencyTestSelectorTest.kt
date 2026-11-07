package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.LatencyTestMethod
import io.nekohasekai.sagernet.ui.LatencyTestRequest
import io.nekohasekai.sagernet.ui.LatencyTestScope
import io.nekohasekai.sagernet.ui.LatencyTestSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyTestSelectorTest {
    private fun node(id: Long, status: Int) = ProxyEntity(id = id, groupId = 7, status = status)

    @Test fun scopesClassifyFailureAndUntestedWithoutLookingAtPing() {
        // A/G are untested; B/F are successful (F deliberately has ping 0);
        // C/D/E/H are all failure variants, including a stale non-zero ping.
        val all = listOf(
            node(1, 0), node(2, 1).apply { ping = 85 }, node(3, 2), node(4, 3),
            node(5, 2), node(6, 1).apply { ping = 0 }, node(7, 0), node(8, 3).apply { ping = 91 },
        )
        fun ids(scope: LatencyTestScope) = LatencyTestSelector.select(
            all, LatencyTestRequest(7, LatencyTestMethod.TCP, scope), supports = { true }
        ).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ids(LatencyTestScope.ALL))
        assertEquals(listOf(3L, 4L, 5L, 8L), ids(LatencyTestScope.FAILED))
        assertEquals(listOf(1L, 7L), ids(LatencyTestScope.UNTESTED))
    }

    @Test fun unknownStatusIsNotUntested() {
        val all = listOf(node(1, 0), node(2, 9))
        val result = LatencyTestSelector.select(
            all, LatencyTestRequest(7, LatencyTestMethod.URL, LatencyTestScope.UNTESTED), supports = { true }
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun activeAndUnsupportedTargetsAreExcludedBeforeReset() {
        val all = listOf(node(1, 0), node(2, 0), node(3, 9))
        val result = LatencyTestSelector.select(
            all, LatencyTestRequest(7, LatencyTestMethod.TCP, LatencyTestScope.UNTESTED),
            activeIds = setOf(1L), supports = { it.id != 2L }
        )
        assertEquals(emptyList<ProxyEntity>(), result)
    }
}
