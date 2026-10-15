package io.nekohasekai.sagernet.ui

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.database.SagerDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkUiRegressionTest {
    private fun database(): SagerDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        val sql = db.openHelper.writableDatabase
        for (g in listOf(11,12)) sql.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,`order`,isSelector,frontProxy,landingProxy) VALUES ($g,$g,0,'group $g',0,0,0,-1,-1)")
        for ((id,g,order) in listOf(Triple(9,11,2),Triple(10,11,1),Triple(20,12,1))) {
            sql.execSQL("INSERT INTO proxy_entities (id,groupId,type,userOrder,tx,rx,status,ping,uuid) VALUES ($id,$g,0,$order,42,84,0,0,'sentinel-$id')")
        }
        return db
    }
    @Test fun clearAndUndoKeepGroupOrderAndUnrelatedNodes() {
        val db = database()
        try {
            val before = db.proxyDao().getByGroup(11).map { it.id to it.userOrder }
            val saved = GroupClearStore.clear(db,11)!!
            assertTrue(db.proxyDao().getByGroup(11).isEmpty())
            assertNotNull(db.groupDao().getById(11))
            assertNotNull(db.proxyDao().getById(20))
            assertTrue(GroupClearStore.restore(db,11,saved))
            assertEquals(before,db.proxyDao().getByGroup(11).map { it.id to it.userOrder })
            assertEquals(42L,db.proxyDao().getById(9)!!.tx)
            assertEquals(84L,db.proxyDao().getById(9)!!.rx)
            assertTrue(GroupClearStore.restore(db,11,saved))
            assertEquals(2,db.proxyDao().getByGroup(11).size)
        } finally { db.close() }
    }
    @Test fun undoDoesNotResurrectDeletedGroupOrOverwriteNewData() {
        val db=database()
        try {
            val saved=GroupClearStore.clear(db,11)!!
            assertTrue(GroupClearStore.restore(db,11,saved))
            db.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET tx=999 WHERE id=9")
            assertTrue(GroupClearStore.restore(db,11,saved))
            assertEquals(999L,db.proxyDao().getById(9)!!.tx)
            GroupClearStore.clear(db,11)
            db.groupDao().deleteById(11)
            assertFalse(GroupClearStore.restore(db,11,saved))
            assertNull(db.proxyDao().getById(9))
        } finally { db.close() }
    }
    @Test fun hiddenAddressPersistsAndMasksBothAddressFamilies() {
        val c=RuntimeEnvironment.getApplication()
        NetworkPrivacy.prefs(c).edit().clear().commit()
        assertFalse(NetworkPrivacy.hidden(c))
        NetworkPrivacy.prefs(c).edit().putBoolean(NetworkPrivacy.HIDE_IP,true).commit()
        assertTrue(NetworkPrivacy.hidden(c))
        assertEquals("*****",NetworkPrivacy.display("103.211.71.72",NetworkPrivacy.hidden(c)))
        assertEquals("*****",NetworkPrivacy.display("2001:db8::1",true))
        assertEquals("103.211.71.72",NetworkPrivacy.display("103.211.71.72",false))
        assertEquals("—",NetworkPrivacy.display(null,false))
        assertEquals("🇭🇰",NetworkPrivacy.flag("香港"))
        assertEquals("🇭🇰",NetworkPrivacy.flag("Hong Kong"))
        assertEquals("🇨🇳",NetworkPrivacy.flag("China"))
    }
}
