package io.nekohasekai.sagernet.ui

import android.app.Application
import android.os.Parcel
import androidx.room.Room
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupRestoreTest {
    private lateinit var db: SagerDatabase
    private lateinit var settings: PublicDatabase

    @Before fun createDatabases() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).allowMainThreadQueries().build()
        settings = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        db.groupDao().insert(listOf(ProxyGroup(id = 1, name = "local", userOrder = 10)))
        db.proxyDao().insert(listOf(node(1, 1)))
        db.rulesDao().insert(listOf(RuleEntity(id = 9, name = "local-rule", outbound = 1)))
        db.tagGroupDao().insert(TagGroup(id = 1, name = "local-classification"))
        db.tagMembershipDao().insert(TagMembership(1, 1))
        settings.keyValuePairDao().put(KeyValuePair().apply { key = "local-setting" }.put("local"))
    }

    @After fun closeDatabases() { db.close(); settings.close() }

    private fun node(id: Long, group: Long): ProxyEntity = ProxyEntity(id = id, groupId = group).putBean(
        SOCKSBean().apply { initializeDefaultValues(); name = "synthetic-$id" }
    )

    private fun backup(
        groups: List<ProxyGroup> = listOf(ProxyGroup(id = 1, name = "imported", userOrder = 1)),
        profiles: List<ProxyEntity> = listOf(node(1, 1)),
    ): JSONObject = JSONObject().apply {
        put("version", 1)
        put("groups", JSONArray(groups.map(BackupCompatibility::encode)))
        put("profiles", JSONArray(profiles.map(BackupCompatibility::encode)))
        // Invalid extension payloads must never even be parsed, much less restored.
        put("tag_groups", "not-an-array")
        put("tag_memberships", "not-an-array")
    }

    private fun restore(json: JSONObject, append: Boolean, profile: Boolean = true,
                        rule: Boolean = false, setting: Boolean = false) =
        BackupRestore.restore(json, profile, rule, setting, append, db, settings)

    private fun localStateIntact() {
        assertEquals("local", db.groupDao().getById(1)!!.name)
        assertNotNull(db.proxyDao().getById(1))
        assertEquals(listOf(1L), db.tagMembershipDao().members(1))
        assertEquals(listOf("local-classification"), db.tagGroupDao().all().map { it.name })
    }

    @Test fun groupsOnlyOverwritesWithOriginalIdsAndDoesNotRestoreClassification() {
        restore(backup(), append = false)
        assertEquals(listOf("imported"), db.groupDao().allGroups().map { it.name })
        assertEquals(1L, db.proxyDao().getAll().single().id)
        assertEquals(1L, db.proxyDao().getAll().single().groupId)
        assertEquals("local-rule", db.rulesDao().allRules().single().name)
        assertNotNull(settings.keyValuePairDao()["local-setting"])
        assertEquals(listOf("local-classification"), db.tagGroupDao().all().map { it.name })
        assertTrue(db.tagMembershipDao().members(1).isEmpty())
    }

    @Test fun appendRemapsCollidingGroupsProfilesAndForwardChainReferences() {
        val chain = ProxyEntity(id = 2, groupId = 1).putBean(ChainBean().apply {
            initializeDefaultValues(); name = "synthetic-chain"; proxies = listOf(1L, 3L)
        })
        // Chain precedes its referenced profiles in the backup.
        restore(backup(profiles = listOf(chain, node(1, 1), node(3, 1))), append = true)
        localStateIntact()
        val importedGroup = db.groupDao().allGroups().single { it.name == "imported" }
        assertNotEquals(1L, importedGroup.id)
        assertTrue(importedGroup.userOrder > 10)
        val imported = db.proxyDao().getByGroup(importedGroup.id)
        assertEquals(3, imported.size)
        assertTrue(imported.none { it.id == 1L })
        val importedChain = imported.single { it.type == ProxyEntity.TYPE_CHAIN }.chainBean!!
        assertEquals(listOf("synthetic-1", "synthetic-3"), importedChain.proxies.map {
            db.proxyDao().getById(it)!!.requireBean().name
        })
        assertTrue(importedChain.proxies.all { id -> imported.any { it.id == id } })
        assertTrue(db.tagMembershipDao().byGroup(importedGroup.id).isEmpty())
    }

    @Test fun appendRemapsGroupFrontLandingAndSelectedRuleOutbound() {
        val json = backup()
        // Encode this fixture in the existing internal group layout, as legacy local
        // backups can contain it. Export still uses the original-format projection.
        val group = ProxyGroup(id = 1, name = "imported", frontProxy = 1, landingProxy = 1)
        val parcel = Parcel.obtain()
        try {
            group.writeToParcel(parcel, 0)
            json.put("groups", JSONArray().put(Util.b64EncodeUrlSafe(parcel.marshall())))
        } finally { parcel.recycle() }
        json.put("rules", JSONArray().put(BackupCompatibility.encode(RuleEntity(id = 2, outbound = 1))))
        restore(json, append = true, rule = true)
        val importedGroup = db.groupDao().allGroups().single { it.name == "imported" }
        val id = db.proxyDao().getByGroup(importedGroup.id).single().id
        assertNotEquals(1L, id)
        assertEquals(id, importedGroup.frontProxy)
        assertEquals(id, importedGroup.landingProxy)
        assertEquals(id, db.rulesDao().allRules().single().outbound)
        localStateIntact()
    }

    @Test fun missingOptionalSectionsAreNoOpEvenWhenTheirOptionsAreSelected() {
        restore(JSONObject().put("version", 1), append = true, rule = true, setting = true)
        localStateIntact()
        assertEquals("local-rule", db.rulesDao().allRules().single().name)
        assertNotNull(settings.keyValuePairDao()["local-setting"])
    }

    @Test fun appendCheckboxAloneNeverImportsConfigurations() {
        restore(backup(), append = true, profile = false)
        localStateIntact()
        assertEquals(1, db.groupDao().allGroups().size)
        assertEquals(1, db.proxyDao().getAll().size)
    }

    @Test fun duplicateOrDanglingSourceIdsRejectBeforeWrites() {
        val cases = listOf(
            backup(groups = listOf(ProxyGroup(id = 1), ProxyGroup(id = 1))),
            backup(profiles = listOf(node(1, 1), node(1, 1))),
            backup(profiles = listOf(node(1, 99))),
            backup(profiles = listOf(ProxyEntity(id = 1, groupId = 1).putBean(ChainBean().apply {
                initializeDefaultValues(); proxies = listOf(999L)
            }))),
        )
        cases.forEach {
            try { restore(it, append = true); fail("must reject ambiguous/dangling IDs") }
            catch (_: IllegalArgumentException) { }
            localStateIntact()
            assertEquals(1, db.groupDao().allGroups().size)
            assertEquals(1, db.proxyDao().getAll().size)
        }
    }

    @Test fun insertionFailureRollsBackNewGroupsProfilesAndRuleReset() {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_backup_rule BEFORE INSERT ON rules
            WHEN NEW.name = 'reject-synthetic' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END
        """.trimIndent())
        val json = backup().put("rules", JSONArray().put(
            BackupCompatibility.encode(RuleEntity(id = 2, name = "reject-synthetic", outbound = 1))
        ))
        for (append in listOf(false, true)) {
            var failed = false
            try { restore(json, append = append, rule = true) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            localStateIntact()
            assertEquals(1, db.groupDao().allGroups().size)
            assertEquals(1, db.proxyDao().getAll().size)
            assertEquals("local-rule", db.rulesDao().allRules().single().name)
        }
    }

    @Test fun settingsRestoreFiltersClassificationKeysAndIgnoresBackupExtensions() {
        val values = listOf(
            KeyValuePair().apply { key = "tag_groups_json" }.put("ignored"),
            KeyValuePair().apply { key = "selectedTagId" }.put(44L),
            KeyValuePair().apply { key = "serverAddress" }.put("example.invalid"),
        )
        val json = JSONObject().put("version", 1)
            .put("settings", JSONArray(values.map(BackupCompatibility::encode)))
            .put("tag_groups", "not-an-array").put("tag_memberships", "not-an-array")
        restore(json, append = false, profile = false, setting = true)
        assertEquals(listOf("serverAddress"), settings.keyValuePairDao().all().map { it.key })
        localStateIntact()
    }

    @Test fun malformedSelectedSettingsAreDecodedBeforeConfigurationOverwrite() {
        val json = backup().put("settings", JSONArray().put("invalid-parcel"))
        var failed = false
        try { restore(json, append = false, setting = true) }
        catch (_: Exception) { failed = true }
        // Robolectric's custom Parcel wire format reports corrupt bytes as an
        // Error, unlike Android's runtime exception. Both must precede writes.
        catch (error: AssertionError) {
            if (error.javaClass.name != "org.robolectric.shadows.ShadowParcel\$UnreliableBehaviorError") throw error
            failed = true
        }
        assertTrue(failed)
        localStateIntact()
    }
}
