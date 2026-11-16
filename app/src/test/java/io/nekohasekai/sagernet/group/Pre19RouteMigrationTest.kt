package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.nekohasekai.sagernet.database.SagerDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Direct upgrades preserve pre19 profiles and later builds' extra tables. No destructive reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class Pre19RouteMigrationTest {
    private fun migration(name: String): Migration = SagerDatabase::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(null) as Migration
    }
    private fun upgrade(from: Int) {
        val context = RuntimeEnvironment.getApplication()
        val name = "pre19-route-upgrade-$from.db"
        context.deleteDatabase(name)
        val schema = JSONObject(javaClass.getResourceAsStream("/database-v8.json")!!.bufferedReader().readText())
            .getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(from) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j)
                            .getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    migration("MIGRATION_8_9_SAFE").migrate(db)
                    migration("MIGRATION_9_10_ORDER_INDEX").migrate(db)
                    if (from >= 11) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS tag_groups(id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                        db.execSQL("INSERT INTO tag_groups VALUES(77,'retained extra table')")
                    }
                    if (from >= 12) migration("MIGRATION_12_13_ROUTE_METADATA").migrate(db)
                    db.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,`order`,isSelector,frontProxy,landingProxy) VALUES (11,1,0,'saved group',0,0,0,-1,-1)")
                    db.execSQL("INSERT INTO proxy_entities (id,groupId,type,userOrder,tx,rx,status,ping,uuid) VALUES (9,11,0,1,42,84,0,0,'sentinel')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        repeat(2) {
            val db = Room.databaseBuilder(context, SagerDatabase::class.java, name)
                .addMigrations(migration("MIGRATION_${from}_13_ROUTE_METADATA"))
                .allowMainThreadQueries().build()
            try {
                assertEquals(13, db.openHelper.writableDatabase.version)
                assertEquals("saved group", db.groupDao().getById(11)!!.name)
                db.openHelper.writableDatabase.query("SELECT uuid,tx,rx FROM proxy_entities WHERE id=9").use {
                    assertTrue(it.moveToFirst()); assertEquals("sentinel", it.getString(0))
                    assertEquals(42, it.getInt(1)); assertEquals(84, it.getInt(2))
                }
                if (from >= 11) db.openHelper.writableDatabase.query("SELECT name FROM tag_groups WHERE id=77").use {
                    assertTrue(it.moveToFirst()); assertEquals("retained extra table", it.getString(0))
                }
            } finally { db.close() }
        }
        context.deleteDatabase(name)
    }
    @Test fun pre19Db10UpgradesWithoutReset() = upgrade(10)
    @Test fun db11ExtraTablesAreRetained() = upgrade(11)
    @Test fun currentDb12CanBeCoveredWithoutDowngradeOrDataLoss() = upgrade(12)
}
