package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 13,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        private val MIGRATION_8_9_SAFE = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`proxy_entities`)").use { c ->
                    val i = c.getColumnIndex("name")
                    while (c.moveToNext()) if (i >= 0) columns += c.getString(i)
                }
                if ("xhttpBean" !in columns) db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `xhttpBean` BLOB DEFAULT NULL")
                if ("oppaBean" !in columns) db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `oppaBean` BLOB DEFAULT NULL")
            }
        }
        private val MIGRATION_9_10_ORDER_INDEX = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `groupOrderId` ON `proxy_entities` (`groupId`, `userOrder`, `id`)")
            }
        }

        /** Direct, idempotent upgrade for pre19 DB10/11/12 to route-capable DB13. */
        private fun routeMetadataMigration(from: Int) = object : Migration(from, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`rules`)").use { cursor ->
                    val index = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) if (index >= 0) columns += cursor.getString(index)
                }
                fun add(name: String, type: String = "TEXT") {
                    if (name !in columns) {
                        val defaultValue = if (type == "INTEGER") "0" else "''"
                        db.execSQL("ALTER TABLE `rules` ADD COLUMN `$name` $type NOT NULL DEFAULT $defaultValue")
                    }
                }
                add("builtinKey")
                add("sourceUrl")
                add("subscriptionState")
                add("lastUpdateError")
                add("cachedRuleCount", "INTEGER")
                add("skippedRuleCount", "INTEGER")
            }
        }

        private val MIGRATION_10_13_ROUTE_METADATA = routeMetadataMigration(10)
        private val MIGRATION_11_13_ROUTE_METADATA = routeMetadataMigration(11)
        private val MIGRATION_12_13_ROUTE_METADATA = routeMetadataMigration(12)
        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(
                    MIGRATION_8_9_SAFE,
                    MIGRATION_9_10_ORDER_INDEX,
                    MIGRATION_10_13_ROUTE_METADATA,
                    MIGRATION_11_13_ROUTE_METADATA,
                    MIGRATION_12_13_ROUTE_METADATA,
                )
//                .addMigrations(*SagerDatabase_Migrations.build())
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
