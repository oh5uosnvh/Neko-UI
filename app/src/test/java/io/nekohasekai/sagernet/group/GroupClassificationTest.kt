package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.GroupClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupClassificationTest {
    @Test fun selectedGroupsStayInOriginalOrderAndBecomeContiguous() {
        assertEquals(
            listOf(1L, 3L, 5L, 7L, 2L, 4L, 6L, 8L, 9L),
            GroupClassification.stackSelected((1L..9L).toList()) { it in setOf(1L, 3L, 5L, 7L) },
        )
    }

    @Test fun tagDragOnlySwapsMemberSlotsWithoutPromotingSubsetToTop() {
        assertEquals(listOf(5L, 2L, 1L, 4L, 3L, 6L),
            GroupClassification.mergeSubsetOrder((1L..6L).toList(), listOf(5L, 1L, 3L)))
        assertEquals(listOf(1L, 2L, 3L),
            GroupClassification.mergeSubsetOrder(listOf(1L, 2L, 3L), listOf(88L)))
    }

    @Test fun customSelectionCanExcludeSimilarNames() {
        val names = listOf("花云", "花云云", "其他")
        val selected = setOf(names[0])
        assertEquals(listOf("花云", "花云云", "其他"), names.filter { it in selected } + names.filter { it !in selected })
    }

    @Test fun numberedNamesClusterStablyWithoutMergingOrDeletingCards() {
        val source = listOf(
            1L to "Other", 2L to "Tokyo-02", 3L to "Keep", 4L to "Tokyo-01", 5L to "Tokyo-03"
        )
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), GroupClassification.stableClusterIds(source))
    }

    @Test fun conservativeClassifierLeavesUnrelatedAndShortNamesAlone() {
        val source = listOf(1L to "A-1", 2L to "A-2", 3L to "Tokyo 2", 4L to "Tokyo 1")
        assertEquals(listOf(1L, 2L, 3L, 4L), GroupClassification.stableClusterIds(source))
    }
}
