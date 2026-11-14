package io.nekohasekai.sagernet.group

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual generated Room DAO/SQLite; all data and subscription sources are synthetic.
 * Run on the CI x86 JVM (never requires the phone's database, network or native core).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class LegacyVmessRecoveryDatabaseTest {
    private lateinit var db: SagerDatabase
    private lateinit var singleton: Any
    private var previousValue: Any? = null
    private var previousInitializer: Any? = null
    private var installed = false
    private var groupId = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        // Replace only the lazy value, preserving/restoring both fields even when it was uninitialized.
        val field = SagerDatabase::class.java.getDeclaredField("instance\$delegate").apply { isAccessible = true }
        singleton = field.get(null)!!
        previousValue = ReflectionHelpers.getField<Any?>(singleton, "_value")
        previousInitializer = ReflectionHelpers.getField<Any?>(singleton, "initializer")
        ReflectionHelpers.setField(singleton, "_value", db)
        ReflectionHelpers.setField(singleton, "initializer", null)
        installed = true
        groupId = db.groupDao().createGroup(ProxyGroup(name = "synthetic-group", userOrder = 41))
    }

    @After fun tearDown() {
        if (installed) {
            ReflectionHelpers.setField(singleton, "_value", previousValue)
            ReflectionHelpers.setField(singleton, "initializer", previousInitializer)
        }
        if (::db.isInitialized) db.close()
    }

    private fun profile() = ProxyEntity(
        groupId = groupId, userOrder = 719, tx = 1234567, rx = 7654321,
        status = 3, ping = 991, uuid = "entity-sentinel", error = "synthetic failure"
    ).putBean(VMessBean().apply {
        initializeDefaultValues(); name = "synthetic-node"; serverAddress = "node.example"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000081"; type = "ws"; security = "tls"
        host = "gateway.example"; sni = "tls.example"; path = ""; encryption = "chacha20-poly1305"
        subscriptionDns = "hosts: {node.example: 192.0.2.81}"; subscriptionDnsSource = "synthetic"
        enableMux = true; muxConcurrency = 19
    }).apply { id = db.proxyDao().addProxy(this) }

    private fun source(profile: ProxyEntity) = profile.vmessBean!!.clone().apply { path = "/recovered" }

    private class ExplicitRepair(private val database: SagerDatabase, private val sources: List<AbstractBean>) {
        suspend fun recover(profile: ProxyEntity): Boolean {
            val old=profile.vmessBean ?: return false
            val snapshot=KryoConverters.serialize(old)
            val repaired=LegacyVmessWsRecovery.replacement(old,sources) ?: return false
            val context=currentCoroutineContext()
            var written=false
            database.runInTransaction {
                context.ensureActive()
                written=LegacyVmessWsRecovery.commitIfUnchanged(database,profile.id,profile.groupId,snapshot,repaired)
            }
            if(written) profile.putBean(repaired)
            return written
        }
    }
    private fun session(group: Long, beans: List<AbstractBean>) = ExplicitRepair(db, beans)

    private fun bytes(id: Long) = KryoConverters.serialize(db.proxyDao().getById(id)!!.vmessBean)

    private fun row(id: Long): Map<String, Any?> =
        db.openHelper.readableDatabase.query("SELECT * FROM proxy_entities WHERE id = ?", arrayOf(id)).use { c ->
            check(c.moveToFirst())
            c.columnNames.withIndex().associate { (i, name) ->
                name to when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                    else -> c.getString(i)
                }
            }
        }

    @Test fun daoChangesOnlyBeanAndFailureStatusNotTrafficOrderGroupOrAnyOtherColumn() {
        val p = profile()
        val before = row(p.id)
        val repaired = source(p)
        assertEquals(1, db.proxyDao().repairLegacyVmessPath(p.id, p.groupId, KryoConverters.serialize(repaired)))
        val after = row(p.id)
        for ((column, value) in before) {
            if (column !in setOf("vmessBean", "status", "ping", "error")) assertEquals(column, value, after[column])
        }
        assertEquals(0L, after["status"]); assertEquals(0L, after["ping"]); assertNull(after["error"])
        assertArrayEquals(KryoConverters.serialize(repaired), bytes(p.id))
    }

    @Test fun daoWrongGroupWrongProtocolAndDeletedRowsAreNoOps() {
        val p = profile()
        val before = row(p.id)
        val data = KryoConverters.serialize(source(p))
        assertEquals(0, db.proxyDao().repairLegacyVmessPath(p.id, p.groupId + 1, data))
        assertEquals(before, row(p.id))
        db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET type = 0 WHERE id = ?", arrayOf(p.id))
        val wrongType = row(p.id)
        assertEquals(0, db.proxyDao().repairLegacyVmessPath(p.id, p.groupId, data))
        assertEquals(wrongType, row(p.id))
        db.proxyDao().deleteById(p.id)
        assertEquals(0, db.proxyDao().repairLegacyVmessPath(p.id, p.groupId, data))
        assertEquals(0L, db.proxyDao().countByGroup(p.groupId))
    }

    @Test fun confirmedExplicitRepairWritesCloneAndDoesNotOverwriteNewerTrafficOrOrder() = runBlocking {
        val stale = profile()
        val current = db.proxyDao().getById(stale.id)!!.apply { tx = 9001; rx = 9002; userOrder = 9003 }
        db.proxyDao().updateProxy(current)
        val before = row(stale.id)
        assertTrue(session(groupId, listOf(source(stale))).recover(stale))
        val after = row(stale.id)
        for ((column, value) in before) {
            if (column !in setOf("vmessBean", "status", "ping", "error")) assertEquals(column, value, after[column])
        }
        assertEquals("/recovered", stale.vmessBean!!.path)
        assertEquals("/recovered", db.proxyDao().getById(stale.id)!!.vmessBean!!.path)
    }

    @Test fun confirmedExplicitRepairRejectsConcurrentCredentialDnsAndPathEditsWithoutErasingThem() = runBlocking {
        val changes: List<VMessBean.() -> Unit> = listOf(
            { uuid = "edited-credential" }, { subscriptionDns = "edited-dns" },
            { path = "/user-edited" }, { customConfigJson = "{}" }, { enableMux = false }
        )
        for (change in changes) {
            val stale = profile()
            val current = db.proxyDao().getById(stale.id)!!.apply { vmessBean!!.apply(change) }
            db.proxyDao().updateProxy(current)
            val before = row(stale.id)
            assertFalse(session(groupId, listOf(source(stale))).recover(stale))
            assertEquals(before, row(stale.id))
            assertEquals("", stale.vmessBean!!.path)
        }
    }

    @Test fun confirmedExplicitRepairRejectsMovedDeletedAndRetypedProfiles() = runBlocking {
        for (kind in 0..2) {
            val stale = profile()
            when (kind) {
                0 -> db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET groupId = ? WHERE id = ?", arrayOf(groupId + 100, stale.id))
                1 -> db.proxyDao().deleteById(stale.id)
                else -> db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET type = 0 WHERE id = ?", arrayOf(stale.id))
            }
            val before = if (kind == 1) null else row(stale.id)
            assertFalse(session(groupId, listOf(source(stale))).recover(stale))
            if (kind == 1) assertNull(db.proxyDao().getById(stale.id)) else assertEquals(before, row(stale.id))
        }
    }

    @Test fun competingRepairsHaveOneWinnerAndNeverReplaceFirstRecoveredPath() = runBlocking {
        val first = profile()
        val second = db.proxyDao().getById(first.id)!!
        val a = session(groupId, listOf(source(first).apply { path = "/first" }))
        val b = session(groupId, listOf(source(second).apply { path = "/second" }))
        val results = listOf(async(Dispatchers.IO) { a.recover(first) }, async(Dispatchers.IO) { b.recover(second) }).awaitAll()
        assertEquals(1, results.count { it })
        val winner = if (results[0]) "/first" else "/second"
        assertEquals(winner, db.proxyDao().getById(first.id)!!.vmessBean!!.path)
        assertFalse(a.recover(db.proxyDao().getById(first.id)!!))
        assertFalse(b.recover(db.proxyDao().getById(first.id)!!))
    }

    @Test fun importPlanRepairsRenamedSourceInPlaceWithoutAppendingOrLosingCurrentCounters() {
        val p = profile()
        val original = bytes(p.id)
        val imported = source(p).apply { name = "renamed-synthetic-source" }
        val plan = VmessSourceRepairPlan.prepare(groupId, listOf(p), listOf(imported))
        assertEquals(groupId, plan.groupId)
        assertEquals(listOf(p.id), plan.candidates.map { it.profileId })
        assertArrayEquals(original, plan.candidates.single().expected)
        assertArrayEquals("Preparing must not write before user confirmation", original, bytes(p.id))
        assertEquals("", p.vmessBean!!.path)

        // Traffic/order may change while the import confirmation is visible.
        db.proxyDao().updateProxy(db.proxyDao().getById(p.id)!!.apply {
            tx = 91001; rx = 91002; userOrder = 91003
        })
        val before = row(p.id)
        assertEquals(setOf(p.id), plan.commitIds(db))
        assertEquals(1L, db.proxyDao().countByGroup(groupId))
        val after = row(p.id)
        for ((column, value) in before) {
            if (column !in setOf("vmessBean", "status", "ping", "error")) assertEquals(column, value, after[column])
        }
        val expected = p.vmessBean!!.clone().apply { path = "/recovered" }
        assertArrayEquals(KryoConverters.serialize(expected), bytes(p.id))
        assertEquals("synthetic-node", db.proxyDao().getById(p.id)!!.vmessBean!!.name)
        assertEquals(0L, after["status"]); assertEquals(0L, after["ping"]); assertNull(after["error"])
        assertEquals("A second confirmation must not report an already repaired active ID", emptySet<Long>(), plan.commitIds(db))
    }

    @Test fun importPlanCapturesProfileIdGroupAndBeanBytesInsteadOfMutableImportObjects() {
        val p = profile()
        val id = p.id
        val imported = source(p)
        val expected = KryoConverters.serialize(imported)
        val plan = VmessSourceRepairPlan.prepare(groupId, listOf(p), listOf(imported))
        imported.path = "/changed-after-confirmation"
        p.id = id + 100000
        p.groupId = groupId + 100000
        p.vmessBean!!.apply { path = "/detached-caller-edit"; uuid = "detached-credential" }
        assertEquals(setOf(id), plan.commitIds(db))
        assertArrayEquals(expected, bytes(id))
        assertEquals(groupId, db.proxyDao().getById(id)!!.groupId)
        assertEquals(1L, db.proxyDao().countByGroup(groupId))
    }

    @Test fun importPlanLeavesOtherGroupNonemptyPathsAndCustomBeansUntouched() {
        val eligible = profile()
        val otherGroup = db.groupDao().createGroup(ProxyGroup(name = "synthetic-other-group", userOrder = 42))
        val foreign = profile().apply { groupId = otherGroup }.also { db.proxyDao().updateProxy(it) }
        val nonempty = profile().apply { vmessBean!!.path = "/user-path" }.also { db.proxyDao().updateProxy(it) }
        val outbound = profile().apply { vmessBean!!.customOutboundJson = "{}" }.also { db.proxyDao().updateProxy(it) }
        val config = profile().apply { vmessBean!!.customConfigJson = "{}" }.also { db.proxyDao().updateProxy(it) }
        val profiles = listOf(eligible, foreign, nonempty, outbound, config)
        val before = profiles.associate { it.id to row(it.id) }
        val plan = VmessSourceRepairPlan.prepare(groupId, profiles, listOf(source(eligible)))
        assertEquals(listOf(eligible.id), plan.candidates.map { it.profileId })
        assertEquals(setOf(eligible.id), plan.commitIds(db))
        for (p in profiles.drop(1)) assertEquals(before[p.id], row(p.id))
        assertEquals(4L, db.proxyDao().countByGroup(groupId))
        assertEquals(1L, db.proxyDao().countByGroup(otherGroup))
    }

    @Test fun importPlanRequiresExplicitNonrootSourceForRootPathAndRejectsAmbiguity() {
        val p = profile().apply { vmessBean!!.path = "/" }.also { db.proxyDao().updateProxy(it) }
        val before = row(p.id)
        val rootSource = source(p).apply { path = "/" }
        for (sources in listOf(emptyList(), listOf(rootSource), listOf(rootSource.clone().apply { path = "" }))) {
            val rejected = VmessSourceRepairPlan.prepare(groupId, listOf(p), sources)
            assertTrue(rejected.candidates.isEmpty())
            assertTrue(rejected.commitIds(db).isEmpty())
            assertEquals(before, row(p.id))
        }
        val explicit = source(p).apply { path = "/source"; name = "renamed-source" }
        val ambiguous = VmessSourceRepairPlan.prepare(groupId, listOf(p), listOf(
            explicit, explicit.clone().apply { path = "/different-source"; name = "second-renamed-source" }
        ))
        assertTrue(ambiguous.candidates.isEmpty())
        assertTrue(ambiguous.commitIds(db).isEmpty())
        val plan = VmessSourceRepairPlan.prepare(groupId, listOf(p), listOf(rootSource, explicit))
        assertEquals(before, row(p.id)) // Detection alone is not permission to repair '/'.
        assertEquals(setOf(p.id), plan.commitIds(db))
        assertEquals("/source", db.proxyDao().getById(p.id)!!.vmessBean!!.path)
        assertEquals(1L, db.proxyDao().countByGroup(groupId))
    }

    @Test fun importPlanSkipsStaleEditsMovedDeletedAndRetypedRowsAndReturnsOnlyWrittenIds() {
        val repaired = profile()
        val changes: List<VMessBean.() -> Unit> = listOf(
            { uuid = "new-credential" }, { subscriptionDns = "new-dns" },
            { path = "/user-edited" }, { customConfigJson = "{}" }, { enableMux = false }
        )
        val edited = changes.map { profile() }
        val moved = profile()
        val deleted = profile()
        val retyped = profile()
        val profiles = listOf(repaired) + edited + listOf(moved, deleted, retyped)
        val plan = VmessSourceRepairPlan.prepare(groupId, profiles, listOf(source(repaired)))
        assertEquals(profiles.map { it.id }, plan.candidates.map { it.profileId })
        edited.zip(changes).forEach { (p, change) ->
            db.proxyDao().updateProxy(db.proxyDao().getById(p.id)!!.apply { vmessBean!!.apply(change) })
        }
        val otherGroup = db.groupDao().createGroup(ProxyGroup(name = "synthetic-moved-group", userOrder = 43))
        db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET groupId = ? WHERE id = ?", arrayOf(otherGroup, moved.id))
        db.proxyDao().deleteById(deleted.id)
        db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET type = 0 WHERE id = ?", arrayOf(retyped.id))
        val skipped = edited + listOf(moved, retyped)
        val before = skipped.associate { it.id to row(it.id) }
        val countBefore = db.proxyDao().countByGroup(groupId)
        val repairedIds = plan.commitIds(db)
        assertEquals("Only committed IDs may trigger an active-service reload", setOf(repaired.id), repairedIds)
        for (p in skipped) {
            assertFalse(repairedIds.contains(p.id))
            assertEquals(before[p.id], row(p.id))
        }
        assertFalse(repairedIds.contains(deleted.id))
        assertNull(db.proxyDao().getById(deleted.id))
        assertEquals("/recovered", db.proxyDao().getById(repaired.id)!!.vmessBean!!.path)
        assertEquals(countBefore, db.proxyDao().countByGroup(groupId))
        assertTrue(plan.commitIds(db).isEmpty())
    }

    @Test fun importPlanCancellationAfterFirstWriteRollsBackEveryCandidateAndFailureStatus() {
        val first = profile()
        val second = profile()
        val plan = VmessSourceRepairPlan.prepare(groupId, listOf(first, second), listOf(source(first)))
        assertEquals(listOf(first.id, second.id), plan.candidates.map { it.profileId })
        val firstBefore = row(first.id)
        val secondBefore = row(second.id)
        val job = Job()
        var checks = 0
        var sawFirstWrite = false
        var completed = false
        try {
            plan.commitIds(db) {
                checks++
                if (checks == 3) {
                    // Entry, first candidate, then second candidate: the first UPDATE is real
                    // but still uncommitted on this same SQLite transaction/connection.
                    assertEquals("/recovered", db.proxyDao().getById(first.id)!!.vmessBean!!.path)
                    assertEquals(0, db.proxyDao().getById(first.id)!!.status)
                    assertEquals(secondBefore, row(second.id))
                    sawFirstWrite = true
                    job.cancel()
                }
                job.ensureActive()
            }
            completed = true
        } catch (_: CancellationException) {
            // Room must end the failed transaction without publishing any repaired IDs.
        } finally {
            job.cancel()
        }
        assertFalse("Cancelled transaction must not return success IDs", completed)
        assertTrue("Cancellation must happen after a candidate was actually written", sawFirstWrite)
        assertEquals(3, checks)
        assertEquals(firstBefore, row(first.id))
        assertEquals(secondBefore, row(second.id))
        assertEquals(2L, db.proxyDao().countByGroup(groupId))
        assertEquals("", first.vmessBean!!.path)
        assertEquals("", second.vmessBean!!.path)
        assertEquals(setOf(first.id, second.id), plan.commitIds(db))
        assertTrue(plan.commitIds(db).isEmpty())
    }

    @Test fun cancellationDuringMatchingMustNotCommitOrMutateCaller() = runBlocking {
        val p = profile()
        val before = row(p.id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = source(p)
        // This barrier runs after recover's current ensureActive(), before transaction entry.
        val blocked = object : AbstractList<AbstractBean>() {
            override val size = 1
            override fun get(index: Int): AbstractBean {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Synthetic source barrier timed out" }
                return s
            }
        }
        val recovery = session(groupId, blocked)
        val job = launch(Dispatchers.IO) { recovery.recover(p) }
        try {
            assertTrue("Recovery did not reach matching", entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally { release.countDown(); withTimeout(15000) { job.join() } }
        assertEquals("Cancelled recovery must not persist", before, row(p.id))
        assertEquals("", p.vmessBean!!.path)
    }
}
