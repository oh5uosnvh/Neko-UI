package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ui.GroupClassification
import io.nekohasekai.sagernet.ui.GroupOrderStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupClassificationPersistenceTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), SagerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun seed(db: SagerDatabase): List<ProxyGroup> =
        listOf("prefix", "Airport A", "unmatched", "airport B", "tail").mapIndexed { index, name ->
            ProxyGroup(name = name, userOrder = index + 1L).apply {
                subscription = SubscriptionBean().apply {
                    initializeDefaultValues()
                    link = "https://example.invalid/sub/$index"
                }
                id = db.groupDao().createGroup(this)
            }
        }

    @Test fun queryOnlyReordersMatchedGroupsAndPreservesNamesSubscriptionsAndUnmatchedOrder() {
        val db = database()
        try {
            val source = seed(db)
            val matches = GroupClassification.matches(source, " airport ") { it.displayName() }.map { it.id }.toSet()
            val reordered = GroupClassification.stackSelected(source) { it.id in matches }
            val saved = GroupOrderStore.save(db, reordered.map { it.id })
            assertEquals(listOf(source[0].id, source[1].id, source[3].id, source[2].id, source[4].id),
                saved.groups.map { it.id })
            assertEquals(source.filterNot { it.id in matches }.map { it.id },
                saved.groups.filterNot { it.id in matches }.map { it.id })
            source.forEach { original ->
                val fresh = db.groupDao().getById(original.id)!!
                assertEquals(original.name, fresh.name)
                assertEquals(original.subscription!!.link, fresh.subscription!!.link)
            }
            assertEquals(saved.groups.map { it.id }, db.groupDao().allGroups().map { it.id })
            assertTrue(GroupOrderStore.save(db, reordered.map { it.id }).changed.isEmpty())
        } finally { db.close() }
    }

    @Test fun allGroupsClassificationKeepsExistingOrderAndOnlyUsesOrderWrites() {
        val db = database()
        try {
            val source = seed(db)
            val matches = GroupClassification.matches(source, "") { it.displayName() }.map { it.id }.toSet()
            val reordered = GroupClassification.stackSelected(source) { it.id in matches }
            assertEquals(source.map { it.id }, reordered.map { it.id })
            assertTrue(GroupOrderStore.save(db, reordered.map { it.id }).changed.isEmpty())
            assertEquals(source.map { it.name }, db.groupDao().allGroups().map { it.name })
        } finally { db.close() }
    }

    @Test fun preparationOrCancelDoesNotWriteToTheDatabase() {
        val db = database()
        try {
            val source = seed(db)
            val matches = GroupClassification.matches(source, "airport") { it.displayName() }
            // Dialog preparation is ID-only; cancelling never calls GroupOrderStore.submit/save.
            GroupClassification.stackSelected(source) { row -> matches.any { it.id == row.id } }
            assertEquals(source.map { it.id }, db.groupDao().allGroups().map { it.id })
            assertEquals(source.map { it.userOrder }, db.groupDao().allGroups().map { it.userOrder })
            assertTrue(GroupClassification.matches(source, "missing") { it.displayName() }.isEmpty())
        } finally { db.close() }
    }
}
