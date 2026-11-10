package io.nekohasekai.sagernet.database

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TagGroupMigrationTest {
    private fun fixture(name: String) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name)
        val json = javaClass.getResourceAsStream("/database-v10.json")!!.bufferedReader().use { it.readText() }
        val schema = JSONObject(json).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.getJSONArray("indices")
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        db.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,subscription,`order`,isSelector,frontProxy,landingProxy) " +
                            "VALUES (11,7,0,'subscription root',1,X'010203',2,1,17,19)")
                        db.execSQL("INSERT INTO proxy_entities (id,groupId,type,userOrder,tx,rx,status,ping,uuid,xhttpBean,oppaBean) " +
                            "VALUES (9,11,0,5,42,84,0,0,'sentinel',X'040506',X'070809')")
                        db.execSQL("INSERT INTO rules (id,name,config,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,ruleset,outbound,packages) " +
                            "VALUES (21,'rule sentinel','saved config',3,1,'example.com','','','','tcp','','','ruleset sentinel',11,'')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun assertRootData(db: SupportSQLiteDatabase) {
        db.query("SELECT userOrder,name,type,subscription,`order`,isSelector,frontProxy,landingProxy FROM proxy_groups WHERE id=11").use {
            assertTrue(it.moveToFirst())
            assertEquals(7L, it.getLong(0))
            assertEquals("subscription root", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(3))
            assertEquals(2, it.getInt(4))
            assertEquals(1, it.getInt(5))
            assertEquals(17L, it.getLong(6))
            assertEquals(19L, it.getLong(7))
        }
        db.query("SELECT groupId,userOrder,uuid,tx,rx,xhttpBean,oppaBean FROM proxy_entities WHERE id=9").use {
            assertTrue(it.moveToFirst())
            assertEquals(11L, it.getLong(0))
            assertEquals(5L, it.getLong(1))
            assertEquals("sentinel", it.getString(2))
            assertEquals(42L, it.getLong(3))
            assertEquals(84L, it.getLong(4))
            assertArrayEquals(byteArrayOf(4, 5, 6), it.getBlob(5))
            assertArrayEquals(byteArrayOf(7, 8, 9), it.getBlob(6))
        }
        db.query("SELECT name,config,userOrder,ruleset,outbound FROM rules WHERE id=21").use {
            assertTrue(it.moveToFirst())
            assertEquals("rule sentinel", it.getString(0))
            assertEquals("saved config", it.getString(1))
            assertEquals(3L, it.getLong(2))
            assertEquals("ruleset sentinel", it.getString(3))
            assertEquals(11L, it.getLong(4))
        }
    }

    @Test fun version10UpgradesNonDestructivelyValidatesRoomSchemaAndReopens() {
        val name = "tag-migration-10-11.db"
        val context = RuntimeEnvironment.getApplication()
        fixture(name)
        try {
            repeat(2) { launch ->
                val room = Room.databaseBuilder(context, SagerDatabase::class.java, name)
                    .addMigrations(SagerDatabase.MIGRATION_10_11_TAG_GROUPS,
                        SagerDatabase.MIGRATION_11_12_BUILTIN_ROUTES)
                    .allowMainThreadQueries().build()
                try {
                    val sql = room.openHelper.writableDatabase // Room validates ALL tables/FKs/indices.
                    assertEquals(12, sql.version)
                    assertRootData(sql)
                    if (launch == 0) {
                        assertTrue(room.tagGroupDao().all().isEmpty())
                        val tag = room.tagStore.create("saved tag")
                        sql.execSQL("INSERT INTO tag_memberships (tagId,groupId) VALUES (${tag.id},11)")
                    } else {
                        val tag = room.tagGroupDao().all().single()
                        assertEquals("saved tag", tag.name)
                        assertEquals(setOf(11L), room.tagStore.members(tag.id))
                    }
                    sql.query("PRAGMA foreign_key_list(`tag_memberships`)").use { cursor ->
                        val references = mutableSetOf<String>()
                        while (cursor.moveToNext()) {
                            references += cursor.getString(cursor.getColumnIndexOrThrow("table"))
                            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
                        }
                        assertEquals(setOf("tag_groups", "proxy_groups"), references)
                    }
                    sql.query("PRAGMA index_info(`index_tag_memberships_groupId`)").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("groupId", it.getString(2))
                        assertFalse(it.moveToNext())
                    }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                } finally { room.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migratedRelationsEnforceCompositePrimaryKeyAndCascadeOnlyReferences() {
        val name = "tag-migration-cascade.db"
        val context = RuntimeEnvironment.getApplication()
        fixture(name)
        val room = Room.databaseBuilder(context, SagerDatabase::class.java, name)
            .addMigrations(SagerDatabase.MIGRATION_10_11_TAG_GROUPS,
            SagerDatabase.MIGRATION_11_12_BUILTIN_ROUTES).allowMainThreadQueries().build()
        try {
            val sql = room.openHelper.writableDatabase
            val a = room.tagStore.create("a")
            val b = room.tagStore.create("b")
            room.tagMembershipDao().insert(TagMembership(a.id, 11))
            room.tagMembershipDao().insert(TagMembership(b.id, 11))
            try {
                room.tagMembershipDao().insert(TagMembership(a.id, 11))
                fail("Composite primary key must reject duplicate memberships")
            } catch (_: SQLiteException) { }
            try {
                room.tagMembershipDao().insert(TagMembership(a.id, 999))
                fail("Foreign key must reject missing roots")
            } catch (_: SQLiteException) { }
            room.tagGroupDao().delete(a.id)
            assertTrue(room.tagStore.members(a.id).isEmpty())
            assertEquals(setOf(11L), room.tagStore.members(b.id))
            assertRootData(sql)
            // The reverse cascade removes memberships in ALL tags, not tag entities.
            room.groupDao().deleteById(11)
            assertTrue(room.tagStore.members(b.id).isEmpty())
            assertNotNull(room.tagGroupDao().get(b.id))
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }
}
