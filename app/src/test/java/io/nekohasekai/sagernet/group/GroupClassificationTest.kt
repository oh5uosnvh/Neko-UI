package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.GroupClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun repeatedLettersAbbreviationsAndDescriptionsFormNamedFamilies() {
        val source = listOf(
            1L to "Other", 2L to "ik", 3L to "ikuuu Z组", 4L to "iku",
            5L to "iKuuu_V2", 6L to "良心|没实力", 7L to "良心云_🍎",
            8L to "良心", 9L to "良心机场", 10L to "良心|名",
            11L to "黑石-年", 12L to "黑石VPN", 13L to "守候网络", 14L to "黑石",
            15L to "守候",
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 14L, 13L, 15L),
            GroupClassification.stableClusterIds(source),
        )
        val preview = GroupClassification.previewText(source) { it.second }
        assertTrue(preview.contains("ik (4)"))
        assertTrue(preview.contains("良心 (5)"))
        assertTrue(preview.contains("黑石 (3)"))
        assertTrue(preview.contains("守候 (2)"))
        assertTrue(preview.contains("Other (1)"))
    }

    @Test fun descriptorVariantsMatchWithoutBareNameAnchor() {
        for (family in listOf(
            listOf("ikuuu_A", "iKuuu_V2", "ikuu|618", "ikuuu Z组"),
            listOf("良心|名", "良心|没实力", "良心云_🍎", "良心云|主任"),
            listOf("黑石VPN", "黑石-年/废"),
            listOf("赔钱机场", "赔钱机场|🥕")
        )) {
            val source = family.mapIndexed { i, name -> i.toLong() to name }
            val clusters = GroupClassification.namedClusters(source) { it.second }
            assertEquals(family.toString(), 1, clusters.size)
            assertEquals(source, clusters.single().members)
        }
    }

    @Test fun oneCharacterOverlapDoesNotMergeOrTransitivelyOvermerge() {
        val source = listOf(
            1L to "甲", 2L to "乙", 3L to "黑石", 4L to "黑石VPN",
            5L to "黑石VPN机场", 6L to "黑石VPN机场备用", 7L to "石头",
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), GroupClassification.stableClusterIds(source))
        val clusters = GroupClassification.namedClusters(source) { it.second }
        assertEquals(listOf("黑石", "黑石VPN机场"), clusters.map { it.label })
        assertEquals(listOf(2, 2), clusters.map { it.members.size })
    }
}
