package io.nekohasekai.sagernet.ui

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupDatabaseMigrationTest {
    private fun migrateFixture(existingColumns: List<String>) {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-fixture-${existingColumns.size}.db"
        context.deleteDatabase(name)
        val json = javaClass.getResourceAsStream("/database-v8.json")!!.bufferedReader().use { it.readText() }
        val schema = JSONObject(json).getJSONObject("database")
        val fixture = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            val table = e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = e.getJSONArray("indices")
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        for (column in existingColumns) db.execSQL("ALTER TABLE proxy_entities ADD COLUMN `$column` BLOB DEFAULT NULL")
                        db.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,`order`,isSelector,frontProxy,landingProxy) VALUES (11,1,0,'saved group',0,0,0,-1,-1)")
                        db.execSQL("INSERT INTO proxy_entities (id,groupId,type,userOrder,tx,rx,status,ping,uuid) VALUES (9,11,0,1,42,84,0,0,'sentinel')")
                        if ("xhttpBean" in existingColumns) db.execSQL("UPDATE proxy_entities SET xhttpBean=X'010203' WHERE id=9")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }
            ).build()
        )
        fixture.writableDatabase
        fixture.close()
        // Exercise the exact migration installed by the production patch.
        val field = SagerDatabase::class.java.getDeclaredField("MIGRATION_8_9_SAFE")
        field.isAccessible = true
        val migration = field.get(null) as Migration
        repeat(2) { // Upgrade and next launch must both work without data loss.
            val room = Room.databaseBuilder(context, SagerDatabase::class.java, name)
                .addMigrations(migration).allowMainThreadQueries().build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(9, db.version)
                db.query("SELECT uuid,tx,rx FROM proxy_entities WHERE id=9").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("sentinel", c.getString(0))
                    assertEquals(42, c.getInt(1))
                    assertEquals(84, c.getInt(2))
                }
                db.query("SELECT name FROM proxy_groups WHERE id=11").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("saved group", c.getString(0))
                }
                if ("xhttpBean" in existingColumns) {
                    db.query("SELECT xhttpBean FROM proxy_entities WHERE id=9").use { c ->
                        assertTrue(c.moveToFirst())
                        assertArrayEquals(byteArrayOf(1,2,3), c.getBlob(0))
                    }
                }
            } finally { room.close() }
        }
        context.deleteDatabase(name)
    }

    @Test fun officialV8UpgradesAndReopens() = migrateFixture(emptyList())
    @Test fun oldModWithXhttpAlreadyPresentKeepsDataAndReopens() = migrateFixture(listOf("xhttpBean"))
    @Test fun oldModWithBothColumnsAlreadyPresentKeepsDataAndReopens() = migrateFixture(listOf("xhttpBean", "oppaBean"))
}
