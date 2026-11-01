package io.nekohasekai.sagernet.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.KaringPresetImporter
import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.MenuInflater
import androidx.appcompat.view.menu.MenuBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class KaringPresetUiRegressionTest {
    @Test fun routeMenuExposesChinaPresetAndAssets() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val menu = MenuBuilder(app)
        MenuInflater(app).inflate(R.menu.add_route_menu, menu)
        assertNotNull(menu.findItem(R.id.action_import_cn_preset))
        assertNotNull(menu.findItem(R.id.action_manage_assets))
    }

    @Test fun chinaAssetHas28RulesAndRetainsUpstreamProcessNames() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val text = app.assets.open("karing/cn.json").bufferedReader().use { it.readText() }
        val preset = KaringPresetImporter.parse(text)
        assertEquals(28, preset.rules.size)
        assertTrue(preset.rules.any { "Telegram.exe" in it.processName })
    }

    @Test fun repeatImportDoesNotOverwriteEditedRowsOrDuplicateThem() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val text = app.assets.open("karing/cn.json").bufferedReader().use { it.readText() }
        val preset = KaringPresetImporter.parse(text)
        val existing = RuleEntity(id = 99, name = "user", userOrder = 12, config = "custom")
        val first = KaringPresetImporter.importInto(listOf(existing), preset, "karing-cn-v1")
        assertEquals(29, first.rules.size)
        val edited = first.rules[1].copy(name = "edited", outbound = 55, enabled = true)
        val repeat = KaringPresetImporter.importInto(first.rules.toMutableList().also { it[1] = edited }, preset, "karing-cn-v1")
        assertEquals(29, repeat.rules.size)
        assertEquals(28, repeat.skippedDuplicates)
        assertEquals(edited, repeat.rules[1])
        assertEquals(existing, repeat.rules[0])
    }
}
