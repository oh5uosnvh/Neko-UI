package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteListRegressionTest {
    @Test fun movingFilteredRulesKeepsOtherGroupSlotsAndData() {
        // Interleaved global priority: filtered adapter indices are not database indices.
        val a = RuleEntity(id = 1, name = "preset A", userOrder = 1)
        val old = RuleEntity(id = 2, name = "legacy custom", userOrder = 2, outbound = 42, enabled = false)
        val b = RuleEntity(id = 3, name = "preset B", userOrder = 3)
        val other = RuleEntity(id = 4, name = "custom B", userOrder = 4)
        val visible = mutableListOf(a, b)
        moveVisibleRoute(visible, 0, 1)
        assertEquals(listOf(3L, 1L), visible.map { it.id })
        assertEquals(listOf(1L, 3L), visible.map { it.userOrder })
        assertEquals(2L, old.userOrder)
        assertEquals(4L, other.userOrder)
        assertEquals("legacy custom", old.name)
        assertEquals(42L, old.outbound)
        assertEquals(false, old.enabled)
        assertEquals(listOf(3L, 2L, 1L, 4L), listOf(a, old, b, other).sortedBy { it.userOrder }.map { it.id })
    }

    @Test fun movingWhileAnotherItemIsPendingDeletionDoesNotStealItsOrder() {
        val first = RuleEntity(id = 1, userOrder = 10)
        val removed = RuleEntity(id = 2, userOrder = 20)
        val last = RuleEntity(id = 3, userOrder = 30)
        moveVisibleRoute(mutableListOf(first, last), 1, 0)
        assertEquals(20L, removed.userOrder)
        assertEquals(listOf(3L, 2L, 1L), listOf(first, removed, last).sortedBy { it.userOrder }.map { it.id })
    }
}
