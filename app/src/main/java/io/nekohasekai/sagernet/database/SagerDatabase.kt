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
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class, TagGroup::class, TagMembership::class],
    version = 11,
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
        /** Additive only: root groups, profiles, subscriptions and rules are untouched. */
        val MIGRATION_10_11_TAG_GROUPS = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tag_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `userOrder` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `tag_memberships` (`tagId` INTEGER NOT NULL, `groupId` INTEGER NOT NULL, PRIMARY KEY(`tagId`, `groupId`), FOREIGN KEY(`tagId`) REFERENCES `tag_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`groupId`) REFERENCES `proxy_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_memberships_groupId` ON `tag_memberships` (`groupId`)")
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(MIGRATION_8_9_SAFE, MIGRATION_9_10_ORDER_INDEX, MIGRATION_10_11_TAG_GROUPS)
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

    /** One transaction/change stream per Room instance, also usable by isolated tests. */
    val tagStore: TagGroupStore.Store by lazy { TagGroupStore.Store(this) }

    abstract fun tagGroupDao(): TagGroup.Queries
    abstract fun tagMembershipDao(): TagMembership.Queries
    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
