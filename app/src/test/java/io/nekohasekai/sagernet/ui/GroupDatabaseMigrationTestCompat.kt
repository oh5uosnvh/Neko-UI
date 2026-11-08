package io.nekohasekai.sagernet.ui

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.nekohasekai.sagernet.database.SagerDatabase
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class GroupDatabaseMigrationTestCompat {
    @Test fun productionMigrationObjectsArePresentForSchema10() {
        val field = SagerDatabase::class.java.getDeclaredField("MIGRATION_9_10_ORDER_INDEX")
        field.isAccessible = true
        assert(field.get(null) != null)
    }
}
