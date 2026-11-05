package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.SettingsCardAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsCardGroupingTest {
    private val context: Context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
    private fun preference(context: Context, name: String) = Preference(context).apply {
        key = name; title = name; isPersistent = false
    }
    private fun screen(context: Context): PreferenceScreen = PreferenceManager(context).let { manager ->
        manager.createPreferenceScreen(context).also { manager.setPreferences(it) }
    }
    private fun groups(adapter: SettingsCardAdapter) = adapter.index.sections.map { range ->
        range.map { adapter.getItem(it)!!.key }
    }
    private fun flush() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun leadingRowsAreOneCardAndCategoryHeadingBelongsToItsWholeRegion() {
        val context = context
        val screen = screen(context)
        screen.addPreference(preference(context, "name"))
        screen.addPreference(preference(context, "type"))
        val category = PreferenceCategory(context).apply { key = "category"; title = "Category" }
        screen.addPreference(category)
        category.addPreference(preference(context, "a"))
        category.addPreference(preference(context, "b"))
        val adapter = SettingsCardAdapter(screen)
        assertEquals(listOf(listOf("name", "type"), listOf("category", "a", "b")), groups(adapter))
        assertEquals(2, adapter.index.sectionAt[4] + 1)
        assertSame(category.getPreference(1), adapter.getItem(4))
        assertEquals(4, adapter.getPreferenceAdapterPosition("b"))
    }

    @Test fun emptyHiddenAndUntitledCategoriesLeaveNoGhostCardAndCanRevive() {
        val context = context
        val screen = screen(context)
        val category = PreferenceCategory(context).apply { key = "dynamic"; title = "Dynamic" }
        screen.addPreference(category)
        val adapter = SettingsCardAdapter(screen)
        assertEquals(0, adapter.itemCount)
        assertTrue(category.isVisible)
        val child = preference(context, "child")
        category.addPreference(child)
        flush()
        assertEquals(listOf(listOf("dynamic", "child")), groups(adapter))
        val stableId = adapter.getItemId(1)
        child.isVisible = false
        flush()
        assertEquals(0, adapter.itemCount)
        assertEquals(RecyclerView.NO_POSITION, adapter.getPreferenceAdapterPosition("dynamic"))
        assertTrue("presentation filtering cannot change source visibility", category.isVisible)
        child.isVisible = true
        flush()
        assertEquals(stableId, adapter.getItemId(1))
        category.title = null
        flush()
        assertEquals(listOf(listOf("child")), groups(adapter))
        assertEquals(0, adapter.getPreferenceAdapterPosition(category))
        category.title = "Restored"
        flush()
        assertEquals(2, adapter.itemCount)
        category.isVisible = false
        flush()
        assertEquals(0, adapter.itemCount)
        category.isVisible = true
        flush()
        assertEquals(2, adapter.itemCount)
        category.removePreference(child)
        flush()
        assertEquals(0, adapter.itemCount)
    }

    @Test fun nestedConsecutiveHeadingsStayWithContentAndNestedBoundariesDoNotOverlap() {
        val context = context
        val screen = screen(context)
        val parent = PreferenceCategory(context).apply { key = "parent"; title = key }
        val child = PreferenceCategory(context).apply { key = "nested"; title = key }
        val empty = PreferenceCategory(context).apply { key = "empty"; title = key }
        screen.addPreference(parent)
        parent.addPreference(empty)
        parent.addPreference(child)
        child.addPreference(preference(context, "one"))
        val sibling = PreferenceCategory(context).apply { key = "sibling"; title = key }
        parent.addPreference(sibling)
        sibling.addPreference(preference(context, "two"))
        val adapter = SettingsCardAdapter(screen)
        assertEquals(listOf(listOf("parent", "nested", "one"), listOf("sibling", "two")), groups(adapter))
        child.getPreference(0).isVisible = false
        flush()
        assertEquals(listOf(listOf("parent", "sibling", "two")), groups(adapter))
        sibling.getPreference(0).isVisible = false
        flush()
        assertEquals(0, adapter.itemCount)
        assertTrue(parent.isVisible && child.isVisible && sibling.isVisible)
    }

    @Test fun realEditorXmlUsesCategoryBoundariesNotOneCardPerRow() {
        val context = context
        for ((resource, count) in listOf(R.xml.group_preferences to 3, R.xml.route_preferences to 2,
            R.xml.webdav_preferences to 1, R.xml.standard_v2ray_preferences to 8,
            R.xml.global_preferences to 6)) {
            val manager = PreferenceManager(context)
            val screen = manager.inflateFromResource(context, resource, null)
            manager.setPreferences(screen)
            val adapter = SettingsCardAdapter(screen)
            assertEquals("resource=$resource", count, adapter.index.sections.size)
            assertTrue(adapter.itemCount > count)
            assertEquals((0 until adapter.itemCount).toList(), adapter.index.sections.flatMap { it.toList() })
        }
    }

    @Test fun ordinaryPayloadUpdatesDoNotRebuildIndexOrReplacePreferenceIdentity() {
        val context = context
        val screen = screen(context)
        val target = preference(context, "target")
        screen.addPreference(target)
        val adapter = SettingsCardAdapter(screen)
        val index = adapter.index
        var delivered: Any? = null
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertEquals(0, positionStart)
                delivered = payload
            }
            override fun onChanged() { fail("row update must not be a full refresh") }
        })
        target.summary = "New summary"
        assertSame(index, adapter.index)
        assertSame(target, delivered)
        assertSame(target, adapter.getItem(0))
    }
}
