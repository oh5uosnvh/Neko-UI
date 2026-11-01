package io.nekohasekai.sagernet.fmt

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.nekohasekai.sagernet.assets.KaringResourceSettings
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KaringResourceResolverSettingsTest {
    private lateinit var context: Context
    private lateinit var settings: KaringResourceSettings
    private lateinit var values: MutableMap<String, Boolean>

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("karing_resource_settings", Context.MODE_PRIVATE).edit().clear().commit()
        values = mutableMapOf()
        val store = object : KaringResourceSettings.BooleanStore {
            override fun getBoolean(key: String): Boolean? = values[key]
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        }
        settings = KaringResourceSettings(context, store)
    }

    private fun resolver() = KaringResourceResolver(context, settings)

    @Test fun remoteIsPerFamilyAndSnapshotIsStable() {
        settings.setUseRemote(KaringRuleFamily.GEOSITE, true)
        settings.setUseRemote(KaringRuleFamily.GEOIP, true)
        val resolver = resolver()
        settings.setUseRemote(KaringRuleFamily.GEOSITE, false)
        val site = resolver.resolveBuiltIn("geosite:cn")!!
        val ip = resolver.resolveBuiltIn("geoip:cn")!!
        assertTrue(site.remote)
        assertTrue(ip.remote)
        assertEquals("karing:geosite:cn", site.tag)
        assertEquals(KaringResourceSettings.remoteUrl(KaringRuleFamily.GEOSITE, "cn"), site.pathOrUrl)
        assertEquals(KaringResourceSettings.remoteUrl(KaringRuleFamily.GEOIP, "cn"), ip.pathOrUrl)
        assertFalse(settings.get(KaringRuleFamily.ACL).useRemote)
        assertFalse(settings.get(KaringRuleFamily.GEOSITE).useRemote)
    }

    @Test fun aclRemoteDoesNotChangeOtherFamilies() {
        settings.setUseRemote(KaringRuleFamily.ACL, true)
        val ref = resolver().resolveBuiltIn("acl:Apple")!!
        assertTrue(ref.remote)
        assertEquals(KaringResourceSettings.remoteUrl(KaringRuleFamily.ACL, "Apple"), ref.pathOrUrl)
        assertFalse(settings.get(KaringRuleFamily.GEOSITE).useRemote)
        assertFalse(settings.get(KaringRuleFamily.GEOIP).useRemote)
    }

    @Test fun disabledExplicitReferenceFailsInsteadOfDisappearing() {
        settings.setEnabled(KaringRuleFamily.ACL, false)
        val error = assertThrows(IllegalStateException::class.java) {
            resolver().resolveBuiltIn("acl:Apple")
        }
        assertTrue(error.message!!.contains("disabled"))
        assertTrue(error.message!!.contains("acl:Apple"))
    }

    @Test fun unknownRemoteCodeFailsBeforeAnyDownload() {
        settings.setUseRemote(KaringRuleFamily.GEOIP, true)
        assertThrows(IllegalStateException::class.java) {
            resolver().resolveBuiltIn("geoip:does-not-exist-123")
        }
    }

    @Test fun regionalSelectionRespectsIndexFamilyAndAutoAppend() {
        var resolver = resolver()
        assertEquals(listOf("geosite:cn", "geoip:cn"), resolver.regionalReferences("CN"))
        assertEquals(listOf("geoip:us"), resolver.regionalReferences("US"))
        assertTrue(resolver.regionalReferences("").isEmpty())
        assertTrue(resolver.regionalReferences("../cn").isEmpty())
        settings.setAutoAppendRegion(KaringRuleFamily.GEOSITE, false)
        settings.setEnabled(KaringRuleFamily.GEOIP, false)
        resolver = resolver()
        assertTrue(resolver.regionalReferences("cn").isEmpty())
    }
}
