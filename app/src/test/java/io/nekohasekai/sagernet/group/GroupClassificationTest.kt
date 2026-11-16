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
        val names = listOf("其他", "花云", "间隔", "花云云", "花云二")
        val selected = setOf("花云", "花云二")
        assertEquals(listOf("其他", "花云", "花云二", "间隔", "花云云"),
            GroupClassification.stackSelected(names) { it in selected })
    }

    @Test fun prefixAndUnmatchedGroupsKeepTheirRelativeOrder() {
        val source = listOf("prefix", "match1", "other1", "match2", "other2")
        val result = GroupClassification.stackSelected(source) { it.startsWith("match") }
        assertEquals(listOf("prefix", "match1", "match2", "other1", "other2"), result)
        assertEquals(source.filterNot { it.startsWith("match") }, result.filterNot { it.startsWith("match") })
    }

    @Test fun allNoneEmptyOrSingleSelectionDoesNotInventClassificationRules() {
        val source = listOf("订阅B", "本地A", "订阅B2")
        assertEquals(source, GroupClassification.stackSelected(source) { true })
        assertEquals(source, GroupClassification.stackSelected(source) { false })
        assertEquals(source, GroupClassification.stackSelected(source) { it == "本地A" })
        assertEquals(emptyList<String>(), GroupClassification.stackSelected(emptyList<String>()) { true })
    }

    @Test fun matchesTrimAndIgnoreCaseButDoNotRenameOrMatchUnrelatedGroups() {
        val source = listOf("Airport One", "其他", "airport Two", "AIRPORT Three")
        val matches = GroupClassification.matches(source, " airport ") { it }
        assertEquals(listOf("Airport One", "airport Two", "AIRPORT Three"), matches)
        assertEquals(source, GroupClassification.matches(source, "  ") { it })
        assertEquals(emptyList<String>(), GroupClassification.matches(source, "missing") { it })
        assertEquals(listOf("Airport One", "airport Two", "AIRPORT Three", "其他"),
            GroupClassification.stackSelected(source) { it in matches })
    }

    @Test fun stableAndIdempotentAcrossRandomSelections() {
        val random = kotlin.random.Random(14219)
        repeat(200) {
            val source = (1L..40L).shuffled(random)
            val chosen = source.shuffled(random).take(random.nextInt(41)).toSet()
            val result = GroupClassification.stackSelected(source) { it in chosen }
            assertEquals(source.toSet(), result.toSet())
            assertEquals(source.filter { it in chosen }, result.filter { it in chosen })
            assertEquals(source.filterNot { it in chosen }, result.filterNot { it in chosen })
            assertEquals(result, GroupClassification.stackSelected(result) { it in chosen })
        }
    }
}
