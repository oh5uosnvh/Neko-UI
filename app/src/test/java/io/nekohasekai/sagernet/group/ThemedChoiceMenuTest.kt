package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.Xml
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.room.Room
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.widget.ChoiceMenuPopup
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.ReselectableSpinner
import io.nekohasekai.sagernet.widget.RouteOutboundMenu
import io.nekohasekai.sagernet.widget.ThemedChoiceAdapter
import io.nekohasekai.sagernet.widget.ThemedChoiceStyle
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

/** Real adapters, inflated rows, popup clicks and Room-backed preference binding; no UI mocks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ThemedChoiceMenuTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private fun color(context: Context, attr: Int): Int {
        val attributes = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            assertTrue("Theme must define attribute $attr", attributes.hasValue(0))
            attributes.getColor(0, Color.MAGENTA)
        } finally { attributes.recycle() }
    }

    private fun themedContext(night: Boolean, theme: Int): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(configuration), theme)
    }

    private fun adapter(context: Context, selected: () -> Int, vararg labels: CharSequence) =
        ThemedChoiceAdapter(context, selected).apply { addAll(*labels) }

    private fun measure(row: View, width: Int, exactly: Boolean = true) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width,
            if (exactly) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }

    private fun assertCorners(drawable: GradientDrawable, radius: Float) {
        // Android 28 getCornerRadii() clones the optional array without a null check when
        // the drawable uses one uniform cornerRadius. Read its state, then check all 8 axes.
        val radii = ReflectionHelpers.getField<FloatArray?>(drawable.constantState, "mRadiusArray")
            ?: FloatArray(8) { drawable.cornerRadius }
        assertEquals(8, radii.size)
        radii.forEach { assertEquals(radius, it, 0.01f) }
    }

    private fun assertRow(row: View, context: Context, text: CharSequence, selected: Boolean) {
        assertTrue("Adapter returns the inflated container, not a bare TextView", row is FrameLayout)
        val label = row.findViewById<TextView>(R.id.choice_menu_label)
        assertNotNull(label)
        assertEquals(text.toString(), label.text.toString())
        assertEquals(selected, row.isSelected)
        assertEquals(selected, label.isSelected)
        assertEquals(color(context, android.R.attr.textColorPrimary), label.currentTextColor)
        // LEGACY Paint/Typeface native getters are no-ops. Inspect the real requested font instead.
        assertEquals(if (selected) Typeface.BOLD else Typeface.NORMAL,
            shadowOf(label.typeface).fontDescription.style)
        val card = label.background as GradientDrawable
        val primary = color(context, R.attr.colorPrimary)
        // 选中项：主题色底 + 主题色描边；未选中项：中性灰底（textColorPrimary 低透明度）、无描边。
        val fill = if (selected) {
            (primary and 0x00ffffff) or ((Color.alpha(primary) * 0.16f).toInt() shl 24)
        } else {
            val foreground = color(context, android.R.attr.textColorPrimary)
            (foreground and 0x00ffffff) or ((Color.alpha(foreground) * 0.09f).toInt() shl 24)
        }
        assertEquals(fill, card.color!!.defaultColor)
        assertTrue(Color.alpha(card.color!!.defaultColor) > 0)
        assertCorners(card, context.resources.getDimension(R.dimen.choice_corner_radius))
        assertEquals(if (selected) context.resources.getDimensionPixelSize(
            R.dimen.card_stroke_width_selected) else 0, shadowOf(card).strokeWidth)
        if (selected) assertEquals(primary, shadowOf(card).strokeColor)
        assertTrue("Only the inset label paints the card", row.background == null ||
            (row.background is ColorDrawable && Color.alpha((row.background as ColorDrawable).color) == 0))
    }

    private fun assertInset(row: View) {
        measure(row, dp(row.context, 240))
        val label = row.findViewById<TextView>(R.id.choice_menu_label)
        assertTrue(label.left > 0)
        assertTrue(label.top > 0)
        assertTrue(label.right < row.width)
        assertTrue(label.bottom < row.height)
    }

    private fun assertBackground(context: Context, background: GradientDrawable): Rect {
        assertEquals(color(context, R.attr.colorLayerHigh), background.color!!.defaultColor)
        assertCorners(background, context.resources.getDimension(R.dimen.popup_corner_radius))
        // XML inflation does not call ShadowGradientDrawable.setStroke(int, int).
        val stroke = ReflectionHelpers.getField<Int>(background.constantState, "mStrokeWidth")
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.card_stroke_width), stroke)
        val strokeColors = ReflectionHelpers.getField<ColorStateList>(
            background.constantState, "mStrokeColors")
        assertTrue(Color.alpha(strokeColors.defaultColor) > 0)
        return Rect().also {
            assertTrue(background.getPadding(it))
            assertTrue(it.left >= stroke && it.right >= stroke)
            assertTrue(it.top > stroke && it.bottom > stroke)
        }
    }

    private fun assertClipping(list: ListView) {
        assertTrue(list.clipToPadding)
        assertTrue(list.clipToOutline)
        assertNull(list.divider)
        assertEquals(0, list.dividerHeight)
        var selector = list.selector
        while (selector is DrawableWrapperCompat) selector = selector.drawable
        assertTrue(selector is ColorDrawable)
        assertEquals(Color.TRANSPARENT, (selector as ColorDrawable).color)
        for ((width, height) in listOf(240 to 200, 180 to 350)) {
            list.layout(0, 0, width, height)
            val outline = Outline()
            list.outlineProvider.getOutline(list, outline)
            val rect = Rect()
            assertTrue(outline.getRect(rect))
            assertEquals(Rect(0, 0, width, height), rect)
            assertTrue(outline.canClip())
            assertEquals(list.resources.getDimension(R.dimen.popup_corner_radius),
                outline.radius, 0.01f)
        }
    }

    private class ActivityFixture : AutoCloseable {
        private val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply {
            setTheme(R.style.Theme_SagerNet_Blue)
            theme.applyStyle(androidx.preference.R.style.PreferenceThemeOverlay, true)
        }
        val host: FrameLayout
        val anchor: TextView
        init {
            controller.setup().visible()
            host = FrameLayout(activity)
            anchor = TextView(activity).apply { text = "outbound" }
            host.addView(anchor, FrameLayout.LayoutParams(160, 48))
            activity.setContentView(host)
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 640)
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun lightAndDarkWithTwoPrimaryColorsTintEveryOptionAndBoldOnlyTheSelection() {
        val primaries = mutableSetOf<Int>()
        val surfaces = mutableSetOf<Int>()
        for (night in listOf(false, true)) {
            for (theme in listOf(R.style.Theme_SagerNet_Blue, R.style.Theme_SagerNet_Amber)) {
                val context = themedContext(night, theme)
                primaries += color(context, R.attr.colorPrimary)
                surfaces += color(context, android.R.attr.colorBackground)
                val parent = FrameLayout(context)
                val entries = arrayOf<CharSequence>("Default", "Direct", "Block", "Choose profile")
                for (selected in entries.indices) {
                    val choices = adapter(context, { selected }, *entries)
                    for (position in entries.indices) {
                        assertRow(choices.getView(position, null, parent), context,
                            entries[position], position == selected)
                        assertRow(choices.getDropDownView(position, null, parent), context,
                            entries[position], position == selected)
                    }
                }
            }
        }
        assertEquals("The two app themes must actually resolve different primary colors", 2, primaries.size)
        assertEquals("Both light and dark surfaces were exercised", 2, surfaces.size)
    }

    @Test fun recycledSelectionReadsLiveLambdaAndClearsStrokeFontAndFlags() {
        val context = themedContext(false, R.style.Theme_SagerNet_Blue)
        val parent = FrameLayout(context)
        var selected = 0
        val choices = adapter(context, { selected }, "First", "Second", "Third")
        val row = choices.getDropDownView(0, null, parent)
        assertRow(row, context, "First", true)
        val oldBackground = row.findViewById<TextView>(R.id.choice_menu_label).background
        selected = 2 // No notifyDataSetChanged: the lambda must be consulted on every bind.
        assertSame(row, choices.getView(1, row, parent))
        assertRow(row, context, "Second", false)
        assertNotSame(oldBackground, row.findViewById<TextView>(R.id.choice_menu_label).background)
        assertSame(row, choices.getDropDownView(2, row, parent))
        assertRow(row, context, "Third", true)
        selected = -1
        assertSame(row, choices.getDropDownView(2, row, parent))
        assertRow(row, context, "Third", false)
        selected = 99
        assertRow(choices.getView(0, row, parent), context, "First", false)
        val foreign = TextView(context)
        val replacement = choices.getView(1, foreign, parent)
        assertNotSame(foreign, replacement)
        assertRow(replacement, context, "Second", false)
    }

    @Test fun firstMiddleLastAndSingleSelectionKeepFourCornersInsideTheTransparentRow() {
        val context = themedContext(true, R.style.Theme_SagerNet_Amber)
        val parent = FrameLayout(context)
        for (count in listOf(1, 3)) {
            val entries = Array<CharSequence>(count) { "Option $it" }
            for (selected in entries.indices) {
                val choices = adapter(context, { selected }, *entries)
                for (position in entries.indices) {
                    val row = choices.getDropDownView(position, null, parent)
                    assertRow(row, context, entries[position], position == selected)
                    assertInset(row)
                }
            }
        }
    }

    @Test fun popupResourceUsesTheCurrentSurfaceAndProtectsAllFourRoundedEdges() {
        val surfaces = mutableSetOf<Int>()
        for (night in listOf(false, true)) {
            val context = themedContext(night, R.style.Theme_SagerNet_Blue)
            val background = ContextCompat.getDrawable(context, R.drawable.bg_spinner_dropdown) as GradientDrawable
            assertBackground(context, background)
            surfaces += background.color!!.defaultColor
        }
        assertEquals(2, surfaces.size)
    }

    @Test fun clipMenuRemovesNativeSelectorAndDividerAndTracksAllFourCornersAfterResize() {
        val context = themedContext(false, R.style.Theme_SagerNet_Blue)
        val list = ListView(context).apply {
            selector = ColorDrawable(Color.RED)
            divider = ColorDrawable(Color.BLACK)
            dividerHeight = 3
        }
        ThemedChoiceStyle.clipMenu(list)
        assertClipping(list)
    }

    @Test fun routeMenuEchoesEveryOutboundAndOneClickCallsBackOnceAfterDismissal() {
        ActivityFixture().use { f ->
            val outbounds = listOf(0L, -1L, -2L, 73L)
            val entries = f.activity.resources.getStringArray(R.array.outbound_entry)
            assertEquals(4, entries.size)
            for ((selected, outbound) in outbounds.withIndex()) {
                for (clicked in entries.indices) {
                    val calls = mutableListOf<Int>()
                    lateinit var popup: ListPopupWindow
                    popup = RouteOutboundMenu.show(f.anchor, outbound) {
                        assertFalse("Selection is immediate but runs after the popup closes", popup.isShowing)
                        calls += it
                    }
                    try {
                        idle()
                        val list = popup.listView!!
                        assertTrue(popup.isShowing)
                        assertTrue(popup.isModal)
                        assertTrue(calls.isEmpty())
                        assertEquals(selected, list.selectedItemPosition)
                        assertEquals(entries.toList(), (0 until list.adapter.count).map {
                            list.adapter.getItem(it).toString()
                        })
                        assertTrue(list.adapter is ThemedChoiceAdapter)
                        for (position in entries.indices) assertRow(
                            list.adapter.getView(position, null, list), f.activity,
                            entries[position], position == selected)
                        assertBackground(f.activity, popup.background as GradientDrawable)
                        assertClipping(list)
                        assertTrue(popup.performItemClick(clicked))
                        assertEquals(listOf(clicked), calls)
                        assertFalse(popup.isShowing)
                        assertFalse(popup.performItemClick(clicked))
                        popup.dismiss()
                        idle()
                        assertEquals(listOf(clicked), calls)
                    } finally { popup.dismiss() }
                }
            }
        }
    }

    @Test fun routeMenuCancellationNeverCallsBackIncludingUnknownOutbound() {
        ActivityFixture().use { f ->
            for (outbound in listOf(0L, -1L, -2L, 73L, -99L)) {
                var calls = 0
                val popup = RouteOutboundMenu.show(f.anchor, outbound) { calls++ }
                assertEquals(if (outbound == 0L) 0 else if (outbound == -1L) 1 else
                    if (outbound == -2L) 2 else 3, popup.listView!!.selectedItemPosition)
                popup.dismiss()
                popup.dismiss()
                idle()
                assertFalse(popup.isShowing)
                assertEquals(0, calls)
            }
        }
    }

    @Test fun largeFontAndLongLabelsAreWidthBoundedAndKeepReadableMultilineText() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val context = themedContext(false, R.style.Theme_SagerNet_Blue)
            val maximum = ThemedChoiceStyle.maximumPopupWidth(context)
            assertTrue(maximum in 1..context.resources.displayMetrics.widthPixels)
            val text = (1..16).joinToString("\n") {
                "A very long profile label 配置名称 must remain readable $it"
            }
            val choices = adapter(context, { 0 }, text)
            val row = choices.getDropDownView(0, null, FrameLayout(context))
            measure(row, maximum, exactly = false)
            val label = row.findViewById<TextView>(R.id.choice_menu_label)
            assertTrue(row.measuredWidth <= maximum)
            assertTrue(label.measuredWidth <= maximum)
            assertTrue(label.maxWidth <= maximum)
            assertEquals(text, label.text.toString())
            assertNull(label.ellipsize)
            assertTrue(label.maxLines > 1)
            assertTrue("Explicit lines survive even with LEGACY's approximate text measurement",
                label.lineCount >= 16)
            assertTrue(label.textSize >= 30 * context.resources.displayMetrics.density)
            assertTrue(label.height > dp(context, 48))
            ActivityFixture().use { f ->
                val popup = RouteOutboundMenu.show(f.anchor, 0L) {}
                try {
                    val padding = Rect()
                    popup.background!!.getPadding(padding)
                    assertTrue(popup.width - padding.left - padding.right <=
                        ThemedChoiceStyle.maximumPopupWidth(f.activity))
                    assertTrue(popup.width <= f.activity.resources.displayMetrics.widthPixels)
                } finally { popup.dismiss() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun bindPreference(f: ActivityFixture, preference: SimpleMenuPreference): Spinner {
        val row = LayoutInflater.from(f.activity).inflate(preference.layoutResource, f.host, false)
        f.host.addView(row)
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
        measure(row, 320)
        idle()
        return row.findViewById(R.id.spinner)
    }

    private fun preferencePopup(preference: SimpleMenuPreference): ChoiceMenuPopup =
        ReflectionHelpers.getField(preference, "choicePopup")

    private fun ChoiceMenuPopup.performItemClick(position: Int): Boolean {
        if (!isShowing) return false
        val list = listView
        return list.performItemClick(list.adapter.getView(position, null, list), position,
            list.adapter.getItemId(position))
    }

    private fun finishPreferenceExit() {
        repeat(16) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)) }
    }

    private fun attachPreference(context: Context, store: RoomPreferenceDataStore,
        preference: SimpleMenuPreference) {
        val manager = PreferenceManager(context).apply { preferenceDataStore = store }
        val screen = manager.createPreferenceScreen(context)
        assertTrue(screen.addPreference(preference))
        manager.setPreferences(screen)
    }

    @Test fun dynamicEntriesPreserveStoredValueAndRebindTheNewPositionWithoutStaleHighlight() {
        ActivityFixture().use { f ->
            val database = Room.inMemoryDatabaseBuilder(f.activity, PublicDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val store = RoomPreferenceDataStore(database.keyValuePairDao())
                store.putString("dynamicChoices", "b")
                val preference = SimpleMenuPreference(f.activity).apply {
                    key = "dynamicChoices"
                    title = "Dynamic choices"
                    entries = arrayOf("Alpha", "Beta", "Gamma")
                    entryValues = arrayOf("a", "b", "c")
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
                attachPreference(f.activity, store, preference)
                val spinner = bindPreference(f, preference)
                assertEquals("b", preference.value)
                assertEquals("Beta", preference.summary.toString())
                assertEquals(1, spinner.selectedItemPosition)
                assertTrue(spinner.adapter is ThemedChoiceAdapter)
                val choices = spinner.adapter as ThemedChoiceAdapter
                val selectedRow = choices.getDropDownView(1, null, f.host)
                assertRow(selectedRow, f.activity, "Beta", true)
                preference.entryValues = arrayOf("b", "c", "a", "d")
                preference.entries = arrayOf("Beta renamed", "Gamma renamed", "Alpha renamed", "Delta")
                assertEquals("b", preference.value)
                assertEquals("b", store.getString("dynamicChoices"))
                assertEquals(4, choices.count)
                assertSame(selectedRow, choices.getDropDownView(1, selectedRow, f.host))
                assertRow(selectedRow, f.activity, "Gamma renamed", false)
                assertRow(choices.getDropDownView(0, null, f.host), f.activity, "Beta renamed", true)
                preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(spinner.parent as View))
                idle()
                assertEquals(0, spinner.selectedItemPosition)
                assertEquals("Beta renamed", preference.summary.toString())
                preference.value = "d"
                preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(spinner.parent as View))
                idle()
                assertEquals("d", store.getString("dynamicChoices"))
                assertEquals(3, spinner.selectedItemPosition)
                assertEquals("Delta", preference.summary.toString())
                assertRow(choices.getView(3, selectedRow, f.host), f.activity, "Delta", true)
            } finally { database.close() }
        }
    }

    @Test fun simplePreferenceRealPopupHonorsVetoAcceptsOnceAndCancellationDoesNotPersist() {
        ActivityFixture().use { f ->
            val database = Room.inMemoryDatabaseBuilder(f.activity, PublicDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val store = RoomPreferenceDataStore(database.keyValuePairDao())
                store.putString("listenerChoices", "a")
                val preference = SimpleMenuPreference(f.activity).apply {
                    key = "listenerChoices"
                    title = "Listener choices"
                    entries = arrayOf("Alpha", "Beta", "Gamma")
                    entryValues = arrayOf("a", "b", "c")
                }
                attachPreference(f.activity, store, preference)
                val spinner = bindPreference(f, preference)
                val calls = mutableListOf<String>()
                var accept = false
                preference.setOnPreferenceChangeListener { _, value ->
                    calls += value.toString()
                    accept
                }
                preference.performClick()
                idle()
                var popup = preferencePopup(preference)
                assertTrue(popup.isShowing)
                assertBackground(f.activity, popup.contentView.background as GradientDrawable)
                assertClipping(popup.listView)
                popup.performItemClick(1)
                assertTrue("Veto listener cannot block the visible exit", calls.isEmpty())
                finishPreferenceExit()
                assertEquals(listOf("b"), calls)
                assertEquals("a", preference.value)
                assertEquals("a", store.getString("listenerChoices"))
                assertFalse(popup.isShowing)
                // A veto leaves both the persisted value and the rebound selection unchanged.
                preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(spinner.parent as View))
                idle()
                calls.clear()
                accept = true
                preference.performClick()
                idle()
                popup = preferencePopup(preference)
                popup.performItemClick(2)
                assertTrue(calls.isEmpty())
                finishPreferenceExit()
                assertEquals(listOf("c"), calls)
                assertEquals("c", preference.value)
                assertEquals("c", store.getString("listenerChoices"))
                assertFalse(popup.isShowing)
                calls.clear()
                preference.performClick()
                idle()
                popup = preferencePopup(preference)
                popup.dismiss()
                finishPreferenceExit()
                assertFalse(popup.isShowing)
                assertTrue(calls.isEmpty())
                assertEquals("c", preference.value)
                assertEquals("c", store.getString("listenerChoices"))
            } finally { database.close() }
        }
    }

    @Test fun outboundPreferenceReselectsProfileOnlyForAnOpenedMenuAndNeverAfterCancelOrBind() {
        ActivityFixture().use { f ->
            val database = Room.inMemoryDatabaseBuilder(f.activity, PublicDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val store = RoomPreferenceDataStore(database.keyValuePairDao())
                store.putString("reselectOutbound", "0")
                val preference = OutboundPreference(f.activity).apply {
                    key = "reselectOutbound"
                    title = "Outbound"
                }
                attachPreference(f.activity, store, preference)
                val spinner = bindPreference(f, preference) as ReselectableSpinner
                val calls = mutableListOf<String>()
                preference.setOnPreferenceChangeListener { _, value -> calls += value.toString(); false }
                preference.value = OutboundPreference.VALUE_SELECT_PROFILE
                spinner.setSelection(3)
                idle()
                assertTrue("Programmatic selection is not profile reselection", calls.isEmpty())
                repeat(2) {
                    preference.performClick()
                    idle()
                    val popup = preferencePopup(preference)
                    assertTrue(popup.isShowing)
                    popup.performItemClick(3)
                    assertEquals(it, calls.size)
                    finishPreferenceExit()
                    assertEquals(List(it + 1) { "3" }, calls)
                    assertEquals("3", preference.value)
                    assertEquals("3", store.getString("reselectOutbound"))
                    assertFalse(popup.isShowing)
                }
                calls.clear()
                preference.performClick()
                idle()
                preferencePopup(preference).dismiss()
                finishPreferenceExit()
                spinner.onWindowFocusChanged(true)
                spinner.setSelection(3)
                idle()
                assertTrue("Cancellation must clear the reselect gate", calls.isEmpty())
                preference.value = "0"
                spinner.setSelection(0)
                idle()
                preference.performClick()
                idle()
                preferencePopup(preference).performItemClick(0)
                finishPreferenceExit()
                assertTrue("Only the profile option supports selecting the same value", calls.isEmpty())
            } finally { database.close() }
        }
    }

    @Test fun allSimpleMenuResourcesInGroupRouteAndGlobalScreensUseTheSharedAdapter() {
        val expected = mapOf(
            R.xml.group_preferences to setOf("groupType", "groupOrder", "subscriptionFilterMode",
                "groupFrontProxy", "groupLandingProxy"),
            R.xml.route_preferences to setOf("routeNetwork", "routeOutbound"),
            R.xml.global_preferences to setOf("nightTheme", "appLanguage", "serviceMode",
                "tunImplementation", "speedInterval", "trafficSniffing", "ipv6Mode", "rulesProvider",
                "domain_strategy_for_remote", "domain_strategy_for_direct", "domain_strategy_for_server",
                "appTLSVersion"),
        )
        ActivityFixture().use { f ->
            for ((resource, expectedKeys) in expected) {
                val tested = mutableSetOf<String>()
                val parser = f.activity.resources.getXml(resource)
                try {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        val preference = when (parser.name) {
                            SimpleMenuPreference::class.java.name -> SimpleMenuPreference(f.activity, Xml.asAttributeSet(parser))
                            OutboundPreference::class.java.name -> OutboundPreference(f.activity, Xml.asAttributeSet(parser))
                            else -> continue
                        }
                        tested += preference.key
                        preference.isPersistent = false
                        val entries = preference.entries
                        val values = preference.entryValues
                        assertNotNull(preference.key, entries)
                        assertNotNull(preference.key, values)
                        assertEquals(preference.key, entries.size, values.size)
                        assertTrue(preference.key, entries.isNotEmpty())
                        preference.value = values[0].toString()
                        val spinner = bindPreference(f, preference)
                        assertTrue(preference.key, spinner.adapter is ThemedChoiceAdapter)
                        val choices = spinner.adapter as ThemedChoiceAdapter
                        assertEquals(preference.key, entries.size, choices.count)
                        for (selected in entries.indices) {
                            preference.value = values[selected].toString()
                            for (position in entries.indices) assertRow(
                                choices.getDropDownView(position, null, f.host), f.activity,
                                entries[position], position == selected)
                        }
                        preference.value = values[0].toString()
                        f.host.removeView(spinner.parent as View)
                    }
                } finally { parser.close() }
                assertEquals("Every current resource option, including OutboundPreference subclasses, was bound",
                    expectedKeys, tested)
            }
        }
    }
}
