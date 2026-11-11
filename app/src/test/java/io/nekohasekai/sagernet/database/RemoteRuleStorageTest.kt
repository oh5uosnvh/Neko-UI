package io.nekohasekai.sagernet.database

import android.app.Application
import androidx.room.Room
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RemoteRuleStorageTest {
    private lateinit var db: SagerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build() }
    @After fun cleanup() { db.close() }
    private fun rule() = RuleEntity(name = "Custom", domains = "full:manual.test", outbound = -1,
        sourceUrl = "https://example.com/rules.yaml").apply { id = db.rulesDao().createRule(this) }
    private fun result() = RemoteRuleParser.parse("payload: ['DOMAIN-SUFFIX,microsoft.com']")
    @Test fun publicationUsesFreshRowAndFailurePreservesLastGoodCache() {
        val r = rule()
        val edited = db.rulesDao().getById(r.id)!!.apply { name = "Renamed"; outbound = -2; domains = "full:edited.test" }
        db.rulesDao().updateRule(edited)
        RemoteRouteUpdater.publish(db, r, result(), null)
        var actual = db.rulesDao().getById(r.id)!!
        assertEquals("Renamed", actual.name); assertEquals(-2L, actual.outbound)
        assertEquals("full:edited.test", actual.domains)
        assertEquals("domain:microsoft.com", actual.remoteDomains)
        RemoteRouteUpdater.publish(db, actual, null, IllegalStateException("HTTP 503"))
        actual = db.rulesDao().getById(r.id)!!
        assertEquals("domain:microsoft.com", actual.remoteDomains)
        assertEquals(1, actual.cachedRuleCount); assertEquals("stale", actual.subscriptionState)
        assertEquals("HTTP 503", actual.lastUpdateError)
    }
    @Test fun changedUrlRejectsOldResultAndDoesNotTouchAnotherRoute() {
        val r = rule(); val other = rule()
        val changed = r.copy(sourceUrl = "https://example.com/new.yaml")
        db.rulesDao().updateRule(changed)
        try { RemoteRouteUpdater.publish(db, r, result(), null); fail("old source must not publish") }
        catch (_: IllegalStateException) { }
        assertEquals("", db.rulesDao().getById(r.id)!!.remoteDomains)
        RemoteRouteUpdater.publish(db, other, result(), null)
        assertEquals(1, db.rulesDao().getById(other.id)!!.cachedRuleCount)
        assertEquals(0, db.rulesDao().getById(r.id)!!.cachedRuleCount)
    }
}
