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
class BulkUndoCountTest {
    @Test fun tenThousandNodesUndoAndCommitHaveConsistentCounts() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        val nodes = (1L..10_000L).map { ProxyEntity(id = it, groupId = 500L, userOrder = it) }
        val removed = nodes.take(8_000)
        try {
            db.proxyDao().insert(nodes)
            assertEquals(10_000L, PendingProfileDeletes.count(500L, db))
            PendingProfileDeletes.stage(removed)
            // Both group card and dropdown call this very function.
            assertEquals(2_000L, PendingProfileDeletes.count(500L, db))
            assertEquals(10_000L, db.proxyDao().countByGroup(500L))
            PendingProfileDeletes.stage(removed) // duplicate click cannot double subtract
            assertEquals(2_000L, PendingProfileDeletes.count(500L, db))
            PendingProfileDeletes.undo(removed)
            assertEquals(10_000L, PendingProfileDeletes.count(500L, db))
            PendingProfileDeletes.stage(removed)
            PendingProfileDeletes.commit(removed, db, false)
            assertEquals(2_000L, db.proxyDao().countByGroup(500L))
            assertEquals(2_000L, PendingProfileDeletes.count(500L, db))
            assertEquals(0, db.proxyDao().profilePageAfter(500L, 0L, 128).count { it.id <= 8_000L })
            assertTrue(PendingProfileDeletes.changes.value > 0)
        } finally {
            PendingProfileDeletes.undo(removed)
            db.close()
        }
    }
}
