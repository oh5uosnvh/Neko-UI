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

    @Test fun customSelectionCanExcludeSimilarNames() {
        val names = listOf("花云", "花云云", "其他")
        val selected = setOf(names[0])
        assertEquals(listOf("花云", "花云云", "其他"), names.filter { it in selected } + names.filter { it !in selected })
    }
}
