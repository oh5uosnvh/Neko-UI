package io.nekohasekai.sagernet.database

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TagGroupStoreTest {
    private lateinit var db: SagerDatabase
    private lateinit var store: TagGroupStore.Store

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        store = TagGroupStore.forDatabase(db)
    }
    @After fun tearDown() = db.close()

    private fun group(name: String = "root", order: Long = 1): ProxyGroup =
        ProxyGroup(name = name, userOrder = order).apply { id = db.groupDao().createGroup(this) }

    private fun profile(groupId: Long) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO proxy_entities (id,groupId,type,userOrder,tx,rx,status,ping,uuid) " +
                "VALUES (91,$groupId,0,1,42,84,0,0,'profile-sentinel')",
        )
    }

    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        fail("Expected ${T::class.java.simpleName}")
    }

    @Test fun namesAreTrimmedValidatedAndEditingSelfIsLegal() {
        val first = store.create("  花 云  ")
        assertEquals("花 云", first.name)
        assertEquals(1L, first.userOrder)
        assertEquals(emptySet<Long>(), store.members(first.id))
        val before = store.changes.value
        fails<IllegalArgumentException> { store.create(" \n\t ") }
        fails<IllegalArgumentException> { store.create(" 花 云 ") }
        store.rename(first.id, " 花 云 ")
        assertEquals(before, store.changes.value)
        val second = store.create("花云") // Internal spaces remain significant.
        fails<IllegalArgumentException> { store.rename(second.id, "花 云") }
        fails<IllegalArgumentException> { store.rename(second.id, " ") }
        store.rename(first.id, " 工作 ")
        assertEquals("工作", store.get(first.id)!!.name)
        assertEquals(listOf(first.id, second.id), store.tags().map { it.id })
    }

    @Test fun relationshipsAreManyToManyWithoutCopyingRootsOrProfiles() {
        val root = group()
        profile(root.id)
        val other = group("other", 2)
        val a = store.create("A")
        val b = store.create("B")
        store.add(a.id, listOf(root.id, other.id))
        store.add(b.id, listOf(root.id))
        assertEquals(setOf(root.id, other.id), store.members(a.id))
        assertEquals(setOf(root.id), store.members(b.id))
        assertEquals(2, db.groupDao().allGroups().size)
        assertEquals(1L, db.proxyDao().countByGroup(root.id))
        store.delete(a.id)!!.restore()
        assertEquals(2, db.groupDao().allGroups().size)
        assertEquals(1L, db.proxyDao().countByGroup(root.id))
    }

    @Test fun batchAddUndoOnlyRemovesNewRelationsAndIsSingleUse() {
        val a = group("a")
        val b = group("b")
        val c = group("c")
        val tag = store.create("tag")
        store.add(tag.id, listOf(a.id))
        val before = store.changes.value
        val undo = store.add(tag.id, listOf(a.id, b.id, b.id, c.id))!!
        assertEquals(before + 1, store.changes.value)
        assertEquals(setOf(a.id, b.id, c.id), store.members(tag.id))
        undo.restore()
        assertEquals(setOf(a.id), store.members(tag.id))
        assertEquals(before + 2, store.changes.value)
        undo.restore()
        assertEquals(before + 2, store.changes.value)
        assertNull(store.add(tag.id, listOf(a.id, a.id)))
        assertNull(store.add(tag.id, emptyList()))
        assertEquals(before + 2, store.changes.value)
    }

    @Test fun batchRemoveUndoRestoresExactlyCurrentTagNotOtherTags() {
        val a = group("a")
        val b = group("b")
        val c = group("c")
        val tag = store.create("tag")
        val other = store.create("other")
        store.add(tag.id, listOf(a.id, b.id))
        store.add(other.id, listOf(a.id, c.id))
        val before = store.changes.value
        val undo = store.remove(tag.id, listOf(a.id, b.id, b.id, c.id))!!
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(setOf(a.id, c.id), store.members(other.id))
        assertEquals(before + 1, store.changes.value)
        undo.restore()
        assertEquals(setOf(a.id, b.id), store.members(tag.id))
        assertEquals(setOf(a.id, c.id), store.members(other.id))
        assertEquals(before + 2, store.changes.value)
        assertNull(store.remove(tag.id, listOf(c.id)))
    }

    @Test fun deleteUndoPreservesIdentityNameOrderAndNewSameNamedEntity() {
        val root = group()
        profile(root.id)
        val first = store.create("first")
        val tag = store.create("same")
        store.create("last")
        store.add(tag.id, listOf(root.id))
        val undo = store.delete(tag.id)!!
        assertNull(store.get(tag.id))
        assertTrue(store.members(tag.id).isEmpty())
        val replacement = store.create(" same ")
        assertNotEquals(tag.id, replacement.id)
        undo.restore()
        assertEquals(tag, store.get(tag.id))
        assertEquals(replacement, store.get(replacement.id))
        assertEquals(setOf(root.id), store.members(tag.id))
        assertTrue(store.members(replacement.id).isEmpty())
        assertEquals(first.id, store.tags().first().id)
        assertEquals(tag.id, store.tags()[1].id)
        assertEquals(1L, db.proxyDao().countByGroup(root.id))
        fails<IllegalArgumentException> { store.create("same") }
        // Even after same-name undo, retaining one's own name remains a legal no-op.
        store.rename(tag.id, " same ")
    }

    @Test fun deletingMissingTagIsNoOp() {
        val before = store.changes.value
        assertNull(store.delete(123))
        assertEquals(before, store.changes.value)
    }

    @Test fun missingForeignKeysRejectEntireBatchAndDoNotNotify() {
        val root = group()
        val tag = store.create("tag")
        val before = store.changes.value
        fails<IllegalArgumentException> { store.add(tag.id, listOf(root.id, 999)) }
        fails<IllegalArgumentException> { store.add(999, listOf(root.id)) }
        fails<IllegalArgumentException> { store.remove(tag.id, listOf(root.id, 999)) }
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
        fails<SQLiteException> { db.tagMembershipDao().insert(TagMembership(tag.id, 999)) }
        fails<SQLiteException> { db.tagMembershipDao().insert(TagMembership(999, root.id)) }
        store.add(tag.id, listOf(root.id))
        fails<SQLiteException> { db.tagMembershipDao().insert(TagMembership(tag.id, root.id)) }
    }

    @Test fun undoCannotOverwriteOccupiedIdAndFailureIsRetryable() {
        val tag = store.create("original")
        val undo = store.delete(tag.id)!!
        val occupied = tag.copy(name = "occupied", userOrder = 99)
        db.tagGroupDao().insert(occupied)
        val before = store.changes.value
        fails<IllegalStateException> { undo.restore() }
        assertEquals(occupied, store.get(tag.id))
        assertEquals(before, store.changes.value)
        db.tagGroupDao().delete(tag.id)
        undo.restore()
        assertEquals(tag, store.get(tag.id))
    }

    @Test fun missingRootDuringDeleteUndoRollsBackEntityAndAllRelations() {
        val a = group("a")
        val b = group("b")
        val tag = store.create("tag")
        store.add(tag.id, listOf(a.id, b.id))
        val undo = store.delete(tag.id)!!
        db.groupDao().deleteById(b.id)
        val before = store.changes.value
        fails<IllegalArgumentException> { undo.restore() }
        assertNull(store.get(tag.id))
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
        db.groupDao().createGroup(b)
        undo.restore()
        assertEquals(tag, store.get(tag.id))
        assertEquals(setOf(a.id, b.id), store.members(tag.id))
    }

    @Test fun addAndRemoveMidBatchFailuresRollbackAndDoNotNotify() {
        val a = group("a")
        val b = group("b")
        val tag = store.create("tag")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_add BEFORE INSERT ON tag_memberships " +
            "WHEN NEW.groupId = ${b.id} BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val before = store.changes.value
        fails<SQLiteException> { store.add(tag.id, listOf(a.id, b.id)) }
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
        sql.execSQL("DROP TRIGGER reject_add")
        store.add(tag.id, listOf(a.id, b.id))
        sql.execSQL("CREATE TRIGGER reject_remove BEFORE DELETE ON tag_memberships " +
            "WHEN OLD.groupId = ${b.id} BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val beforeRemove = store.changes.value
        fails<SQLiteException> { store.remove(tag.id, listOf(a.id, b.id)) }
        assertEquals(setOf(a.id, b.id), store.members(tag.id))
        assertEquals(beforeRemove, store.changes.value)
    }

    @Test fun deleteUndoMidRestoreFailureRollsBackAndCanRetry() {
        val a = group("a")
        val b = group("b")
        val tag = store.create("tag")
        store.add(tag.id, listOf(a.id, b.id))
        val undo = store.delete(tag.id)!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON tag_memberships " +
            "WHEN NEW.groupId = ${b.id} BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val before = store.changes.value
        fails<SQLiteException> { undo.restore() }
        assertNull(store.get(tag.id))
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
        sql.execSQL("DROP TRIGGER reject_restore")
        undo.restore()
        assertEquals(tag, store.get(tag.id))
        assertEquals(setOf(a.id, b.id), store.members(tag.id))
    }

    @Test fun reorderPersistsSnapshotAndRetainsTagsAddedWhileDragging() {
        val first = store.create("first")
        val gone = store.create("gone")
        val third = store.create("third")
        store.delete(gone.id)
        val newest = store.create("new")
        store.reorder(listOf(third.id, gone.id, third.id, first.id))
        assertEquals(listOf(third.id, first.id, newest.id), store.tags().map { it.id })
        assertEquals(listOf(1L, 2L, 3L), store.tags().map { it.userOrder })
        val before = store.changes.value
        store.reorder(listOf(third.id, first.id, newest.id))
        assertEquals(before, store.changes.value)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_order BEFORE UPDATE OF userOrder ON tag_groups " +
            "WHEN OLD.id = ${third.id} BEGIN SELECT RAISE(ABORT, 'forced'); END")
        fails<SQLiteException> { store.reorder(listOf(newest.id, first.id, third.id)) }
        assertEquals(listOf(third.id, first.id, newest.id), store.tags().map { it.id })
        assertEquals(before, store.changes.value)
    }

    @Test fun rootCreationAndMembershipShareCommitAndFailedCreateDoesNotMutateCaller() {
        val tag = store.create("tag")
        val root = ProxyGroup(name = "new root")
        val created = store.createRootGroup(root, tag.id)
        assertSame(root, created)
        assertNotEquals(0L, root.id)
        assertNotNull(db.groupDao().getById(root.id))
        assertEquals(setOf(root.id), store.members(tag.id))
        val before = store.changes.value
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_create BEFORE INSERT ON tag_memberships " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val failed = ProxyGroup(name = "failed", userOrder = 88)
        fails<SQLiteException> { store.createRootGroup(failed, tag.id) }
        assertEquals(0L, failed.id)
        assertEquals(88L, failed.userOrder)
        assertEquals(1, db.groupDao().allGroups().size)
        assertEquals(before, store.changes.value)
        fails<IllegalArgumentException> { store.createRootGroup(ProxyGroup(name = "missing tag"), 999) }
        assertEquals(1, db.groupDao().allGroups().size)
        assertEquals(before, store.changes.value)
        store.createRootGroup(ProxyGroup(name = "global"), null)
        assertEquals(before + 1, store.changes.value)
    }

    @Test fun globalDeleteCascadesEveryTagAndProfilesInOneBatchNotification() {
        val a = group("a")
        val b = group("b")
        val keep = group("keep")
        profile(a.id)
        val first = store.create("first")
        val second = store.create("second")
        store.add(first.id, listOf(a.id, b.id, keep.id))
        store.add(second.id, listOf(a.id, b.id))
        val before = store.changes.value
        store.deleteRootGroups(listOf(a.id, b.id, a.id))
        assertEquals(listOf(keep.id), db.groupDao().allGroups().map { it.id })
        assertEquals(setOf(keep.id), store.members(first.id))
        assertTrue(store.members(second.id).isEmpty())
        assertEquals(0L, db.proxyDao().countByGroup(a.id))
        assertEquals(before + 1, store.changes.value)
        store.deleteRootGroups(listOf(keep.id))
        assertTrue(store.members(first.id).isEmpty())
        assertEquals(before + 2, store.changes.value)
    }

    @Test fun globalDeleteMidTransactionFailureKeepsRootsProfilesAndAllRelations() {
        val a = group("a")
        val b = group("b")
        profile(a.id)
        val tag = store.create("tag")
        store.add(tag.id, listOf(a.id, b.id))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_global BEFORE DELETE ON proxy_groups " +
            "WHEN OLD.id = ${b.id} BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val before = store.changes.value
        fails<SQLiteException> { store.deleteRootGroups(listOf(a.id, b.id)) }
        assertNotNull(db.groupDao().getById(a.id))
        assertNotNull(db.groupDao().getById(b.id))
        assertEquals(setOf(a.id, b.id), store.members(tag.id))
        assertEquals(1L, db.proxyDao().countByGroup(a.id))
        assertEquals(before, store.changes.value)
    }

    @Test fun profileImportFailureRollsBackRootMembershipAndEarlierProfiles() {
        val tag = store.create("tag")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_profile BEFORE INSERT ON proxy_entities " +
            "WHEN NEW.uuid = 'bad' BEGIN SELECT RAISE(ABORT, 'forced'); END")
        val profiles = listOf(ProxyEntity(uuid = "good"), ProxyEntity(uuid = "bad"))
        val candidate = ProxyGroup(name = "import")
        val before = store.changes.value
        fails<SQLiteException> { store.createRootGroup(candidate, tag.id, profiles) }
        assertEquals(0L, candidate.id)
        assertTrue(db.groupDao().allGroups().isEmpty())
        assertTrue(db.proxyDao().getAll().isEmpty())
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
        sql.execSQL("DROP TRIGGER reject_profile")
        store.createRootGroup(candidate, tag.id, profiles)
        assertEquals(2L, db.proxyDao().countByGroup(candidate.id))
        assertEquals(setOf(candidate.id), store.members(tag.id))
        assertEquals(before + 1, store.changes.value)
        assertTrue(profiles.all { it.id == 0L && it.groupId == 0L })
    }

    @Test fun nestedStoreWriteIsRejectedSoOuterRollbackCannotPublishChanges() {
        val tag = store.create("tag")
        val root = group()
        val before = store.changes.value
        fails<IllegalStateException> {
            db.runInTransaction { store.add(tag.id, listOf(root.id)) }
        }
        assertTrue(store.members(tag.id).isEmpty())
        assertEquals(before, store.changes.value)
    }

    @Test fun oneDatabaseHasOneChangeStreamAndConcurrentDuplicateCreateHasOneWinner() {
        assertSame(store, TagGroupStore.forDatabase(db))
        assertSame(store.changes, db.tagStore.changes)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(2) {
            thread {
                try { start.await(); store.create("same") }
                catch (e: Throwable) { errors.add(e) }
                finally { done.countDown() }
            }
        }
        start.countDown()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, store.tags().size)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IllegalArgumentException)
        assertEquals(1L, store.changes.value)
    }
}
