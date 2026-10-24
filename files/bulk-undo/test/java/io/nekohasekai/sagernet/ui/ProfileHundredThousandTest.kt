package io.nekohasekai.sagernet.ui

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.database.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProfileHundredThousandTest {
    @Test fun repeatedHundredThousandImportRemoveUndoCommit() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        val records = (1..100_000).map { ProxyEntity(groupId = 700L) }
        val started = System.nanoTime()
        try {
            repeat(3) { cycle ->
                ProfileBatchStore.appendEntities(db, 700L, records)
                val ids = db.proxyDao().getIdsByGroup(700L)
                assertEquals(100_000, ids.size)
                val removed = ids.take(80_000).map { ProxyEntity(id = it, groupId = 700L) }
                repeat(10) {
                    PendingProfileDeletes.stage(removed)
                    PendingProfileDeletes.stage(removed)
                    assertEquals(20_000L, PendingProfileDeletes.count(700L, db))
                    PendingProfileDeletes.undo(removed)
                    assertEquals(100_000L, PendingProfileDeletes.count(700L, db))
                }
                PendingProfileDeletes.stage(removed)
                PendingProfileDeletes.commit(removed, db, false)
                assertEquals(20_000L, PendingProfileDeletes.count(700L, db))
                assertEquals(20_000L, db.proxyDao().countByGroup(700L))
                db.proxyDao().deleteAll(700L)
                assertEquals(0L, PendingProfileDeletes.count(700L, db))
                println("stress cycle=${cycle + 1} nodes=100000 removed=80000 undo=10 PASS")
            }
            println("stress total milliseconds=" + (System.nanoTime() - started) / 1_000_000)
        } finally {
            PendingProfileDeletes.undo(records)
            db.close()
        }
    }
}
