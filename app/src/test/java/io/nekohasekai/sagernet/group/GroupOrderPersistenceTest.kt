package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ui.GroupOrderStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupOrderPersistenceTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), SagerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun create(db: SagerDatabase, name: String, order: Long): ProxyGroup =
        ProxyGroup(name = name, userOrder = order).apply { id = db.groupDao().createGroup(this) }

    @Test fun stalePopupOrderCannotOverwriteNewDnsNameOrLinkedProxies() {
        val db = database()
        try {
            val first = create(db, "first", 1)
            val second = create(db, "second", 2)
            val third = create(db, "third", 3)
            val popupSnapshot = db.groupDao().allGroups()
            val edited = db.groupDao().getById(first.id)!!.apply {
                name = "edited while popup is open"
                frontProxy = 71L
                landingProxy = 72L
                subscription = SubscriptionBean().apply {
                    initializeDefaultValues()
                    directDnsResolver = "tls://192.0.2.53"
                    link = "https://subscription.example/new"
                }
            }
            db.groupDao().updateGroup(edited)
            val saved = GroupOrderStore.save(db, popupSnapshot.map { it.id }.reversed())
            assertEquals(listOf(third.id, second.id, first.id), saved.groups.map { it.id })
            assertEquals(listOf(1L, 2L, 3L), saved.groups.map { it.userOrder })
            val fresh = db.groupDao().getById(first.id)!!
            assertEquals(edited.name, fresh.name)
            assertEquals(71L, fresh.frontProxy)
            assertEquals(72L, fresh.landingProxy)
            assertEquals(edited.subscription!!.directDnsResolver, fresh.subscription!!.directDnsResolver)
            assertEquals(edited.subscription!!.link, fresh.subscription!!.link)
            assertEquals(listOf(1L, 2L, 3L), popupSnapshot.map { it.userOrder })
        } finally { db.close() }
    }

    @Test fun deletionAndInsertionDuringDragUseCurrentDatabaseRows() {
        val db = database()
        try {
            val first = create(db, "first", 10)
            val deleted = create(db, "deleted", 20)
            val third = create(db, "third", 30)
            val request = listOf(third.id, deleted.id, first.id)
            db.groupDao().deleteById(deleted.id)
            val added = create(db, "added during drag", 40)
            GroupOrderStore.save(db, request)
            assertEquals(listOf(third.id, first.id, added.id), db.groupDao().allGroups().map { it.id })
            assertNull(db.groupDao().getById(deleted.id))
            assertTrue(GroupOrderStore.save(db, listOf(third.id, first.id, added.id)).changed.isEmpty())
        } finally { db.close() }
    }

    @Test fun aMidTransactionFailureRollsBackAllUserOrders() {
        val db = database()
        try {
            val first = create(db, "first", 1)
            val second = create(db, "second", 2)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_order BEFORE UPDATE OF userOrder ON proxy_groups " +
                    "WHEN OLD.id = ${first.id} BEGIN SELECT RAISE(ABORT, 'forced test failure'); END",
            )
            try {
                GroupOrderStore.save(db, listOf(second.id, first.id))
                fail("Expected the forced write failure")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals(listOf(first.id, second.id), db.groupDao().allGroups().map { it.id })
                assertEquals(listOf(1L, 2L), db.groupDao().allGroups().map { it.userOrder })
            }
        } finally { db.close() }
    }
}
