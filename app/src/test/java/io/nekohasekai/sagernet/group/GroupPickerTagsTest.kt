package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupPickerTagsTest {
    @Test fun navigationBackReturnsOneLevelAndDeletionCannotSelectARoot() {
        val navigation = GroupPickerNavigation()
        assertFalse(navigation.back())
        navigation.tags()
        navigation.members(42L)
        assertTrue(navigation.back())
        assertEquals(GroupPickerNavigation.Page.Tags, navigation.page)
        assertTrue(navigation.back())
        assertEquals(GroupPickerNavigation.Page.Configuration, navigation.page)
        navigation.members(42L)
        assertFalse(navigation.reconcile(listOf(TagGroup(42L, "renamed", 2L))))
        assertTrue(navigation.reconcile(listOf(TagGroup(99L, "same name", 1L))))
        assertEquals(GroupPickerNavigation.Page.Tags, navigation.page)
    }

    @Test fun filteringUsesOriginalGlobalOrderAndOriginalObjectsNotMembershipOrder() {
        val roots = listOf(ProxyGroup(id = 55L, name = "first", userOrder = 1),
            ProxyGroup(id = 7L, name = "second", userOrder = 2),
            ProxyGroup(id = 99L, name = "third", userOrder = 3))
        val filtered = tagPickerMembers(roots, linkedSetOf(99L, 55L, 500L))
        assertEquals(listOf(55L, 99L), filtered.map { it.id })
        assertSame(roots[0], filtered[0])
        assertSame(roots[2], filtered[1])
        assertEquals(listOf(55L, 7L, 99L), roots.map { it.id })
        assertTrue(tagPickerMembers(roots, emptySet()).isEmpty())
        assertEquals(listOf(8L, 1L), tagPickerTags(listOf(TagGroup(1L, "Z", 20L),
            TagGroup(8L, "A", 3L))).map { it.id })
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun headerHasTwoExactHalfWidthEntriesAndSeparateBackTagIconTitle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var configuration = 0
            var tags = 0
            var back = 0
            val context = activity.get()
            val header = GroupPickerHeader(context, Color.BLACK, { ColorDrawable(Color.GRAY) },
                { configuration++ }, { tags++ }, { back++ })
            header.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            header.layout(0, 0, 240, header.measuredHeight)
            val entries = header.getChildAt(0) as LinearLayout
            assertEquals(2, entries.childCount)
            assertEquals(entries.getChildAt(0).width, entries.getChildAt(1).width)
            for (i in 0..1) {
                val params = entries.getChildAt(i).layoutParams as LinearLayout.LayoutParams
                assertEquals(0, params.width)
                assertEquals(1f, params.weight, 0f)
            }
            entries.getChildAt(1).performClick()
            assertEquals(1, tags)
            assertEquals(0, configuration)
            header.bind(GroupPickerNavigation.Page.Tags, null)
            assertEquals(View.VISIBLE, entries.visibility)
            assertEquals(View.GONE, header.getChildAt(1).visibility)
            val backRow = header.getChildAt(1) as LinearLayout
            header.bind(GroupPickerNavigation.Page.Members(42L), "Flower")
            assertEquals(View.VISIBLE, descendants(backRow).filterIsInstance<ImageView>().single().visibility)
            assertNotNull(descendants(backRow).filterIsInstance<ImageView>().single().drawable)
            assertEquals("Flower", descendants(backRow).filterIsInstance<TextView>()
                .first { it.text.toString() == "Flower" }.text.toString())
            backRow.performClick()
            assertEquals(1, back)
            assertEquals(0, configuration)
        } finally { activity.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun lockedMemberHeaderShowsRedBangAndTapExplainsUnlock() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var locked: Long? = 42L
            var back = 0
            var lockTap = 0
            val header = GroupPickerHeader(activity.get(), Color.BLACK, { ColorDrawable(Color.GRAY) },
                {}, {}, { back++ }, { locked }, { lockTap++ })
            header.bind(GroupPickerNavigation.Page.Members(42L), "Flower")
            val row = header.getChildAt(1) as ViewGroup
            val bang = descendants(row).filterIsInstance<TextView>().first { it.text.toString() == "!" }
            assertEquals(View.VISIBLE, bang.visibility)
            assertEquals(Color.RED, (bang.background as android.graphics.drawable.GradientDrawable)
                .color!!.defaultColor)
            bang.performClick()
            assertEquals(1, lockTap)
            assertEquals("已锁定当前标签组▶长按解除锁定", ShadowToast.getTextOfLatestToast())
            row.performClick()
            assertEquals(1, back)
            assertEquals(42L, locked)
            locked = null
            header.bind(GroupPickerNavigation.Page.Members(42L), "Flower")
            assertEquals(View.GONE, bang.visibility)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun memberTitleLongPressUsesExistingLockBridgeWithoutChangingShortBack() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var toggled: Long? = null
            var back = 0
            GroupPickerTagScopeBridge.onLongPress = { id, _ -> toggled = id; true }
            val header = GroupPickerHeader(activity.get(), Color.BLACK, { ColorDrawable(Color.GRAY) },
                {}, {}, { back++ })
            header.bind(GroupPickerNavigation.Page.Members(77L), "Work")
            val title = descendants(header).filterIsInstance<TextView>().first { it.text.toString() == "Work" }
            assertFalse("Decorative title must not steal short taps", title.isLongClickable)
            val row = header.getChildAt(1) as View
            assertTrue(row.performLongClick())
            assertEquals(77L, toggled)
            assertFalse(title.performClick())
            assertEquals(0, back)
            row.performClick()
            assertEquals(1, back)
        } finally {
            GroupPickerTagScopeBridge.onLongPress = null
            activity.pause().stop().destroy()
        }
    }

    @Test fun reopeningLockedNavigationStartsAtMembersAndBackKeepsLockModel() {
        val navigation = GroupPickerNavigation()
        var locked: Long? = 18L
        fun reopen() { navigation.members(locked!!) }
        reopen()
        assertEquals(GroupPickerNavigation.Page.Members(18L), navigation.page)
        assertTrue(navigation.back())
        assertEquals(GroupPickerNavigation.Page.Tags, navigation.page)
        assertEquals(18L, locked)
        reopen()
        assertEquals(GroupPickerNavigation.Page.Members(18L), navigation.page)
        locked = null
        assertFalse(GroupPickerTagScope.valid(locked, listOf(TagGroup(18L, "gone", 1L))))
    }

    @Test fun tagPickerHeadersKeepShortBackAndLockedMarkerOnlyForMatchingTag() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = activity.get()
            val recycler = GroupPickerRecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
            val clicked = ArrayList<Long>()
            val adapter = TagPickerAdapter(context, Color.BLACK, { ColorDrawable(Color.GRAY) }, { clicked += it })
            recycler.adapter = adapter
            adapter.replace(listOf(TagGroup(91, "Work", 2), TagGroup(72, "Flower", 1)), recycler,
                mapOf(72L to 3L, 91L to 9L))
            assertEquals(listOf(72L, 91L), adapter.ids)
            val holder = adapter.onCreateViewHolder(recycler, 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("Flower·3", holder.label.text.toString())
            assertEquals("≡", holder.handle.text.toString())
            assertSame(holder.nameCard, holder.handle.parent)
            assertFalse(holder.label.isLongClickable)
            adapter.replace(listOf(TagGroup(72, "Renamed", 3), TagGroup(91, "Work", 1)), recycler)
            holder.label.performClick()
            assertEquals(listOf(72L), clicked)
            adapter.onBindViewHolder(holder, 1)
            assertEquals("Renamed·3", holder.label.text.toString())
            adapter.replace(listOf(TagGroup(91, "Work", 1)), recycler)
            holder.label.performClick()
            assertEquals(listOf(72L), clicked)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun memberRowsKeepCountsAndCurrentSelectionButHaveNoReorderHandle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = activity.get()
            val recycler = GroupPickerRecyclerView(context)
            var clicked: Long? = null
            val adapter = GroupPickerAdapter(context, listOf(ProxyGroup(id = 700, name = "Root")),
                { 700L }, { ColorDrawable(Color.GRAY) }, Color.BLACK, Color.DKGRAY, { clicked = it })
            adapter.reorderable = false
            adapter.updateCounts(mapOf(700L to 12L))
            val holder = adapter.onCreateViewHolder(recycler, 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals(View.GONE, holder.handle.visibility)
            assertTrue(holder.row.isSelected)
            assertEquals("Root·12", holder.label.text.toString())
            holder.row.performClick()
            assertEquals(700L, clicked)
            assertNull(adapter.startDrag)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun cancelledBackNeverNavigatesAndReleaseOnlyPopsOnePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val surface = GroupPickerSurface(activity.get())
            val navigation = GroupPickerNavigation().apply { members(42L) }
            surface.onBack = { navigation.back() }
            assertTrue(surface.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertEquals(GroupPickerNavigation.Page.Members(42L), navigation.page)
            val cancelled = KeyEvent(0, 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
                0, 0, KeyEvent.FLAG_CANCELED)
            assertTrue(surface.dispatchKeyEvent(cancelled))
            assertEquals(GroupPickerNavigation.Page.Members(42L), navigation.page)
            surface.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertEquals(GroupPickerNavigation.Page.Tags, navigation.page)
        } finally { activity.pause().stop().destroy() }
    }
}
