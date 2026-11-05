package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SettingsCardContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private fun source(path: String) = File(app, "src/main/$path").readText()

    @Test fun everyPreferenceHostUsesCardsAndKeepsExistingPressSanitizer() {
        for (file in listOf("SettingsPreferenceFragment.kt", "GroupSettingsActivity.kt",
            "RouteSettingsActivity.kt", "WebDAVSettingsActivity.kt", "profile/ProfileSettingsActivity.kt")) {
            val text = source("java/io/nekohasekai/sagernet/ui/$file")
            assertTrue(file, text.contains("override fun onCreateAdapter("))
            assertTrue(file, text.contains("createSettingsCardAdapter(preferenceScreen)"))
            assertTrue(file, text.contains("settingsRowsInflater(inflater, parent)"))
            assertTrue(file, text.contains(".withoutSettingsRowPress()"))
        }
        val adapter = source("java/io/nekohasekai/sagernet/widget/SettingsCardAdapter.kt")
        assertTrue(adapter.contains("setDivider(null)"))
        assertTrue(adapter.contains("setDividerHeight(0)"))
        assertTrue(adapter.contains("source.onBindViewHolder(holder, index.sourcePositions[position])"))
        assertTrue(adapter.contains("notifyItemChanged(position, payload)"))
        assertFalse(adapter.contains("itemAnimator = null"))
        assertFalse(adapter.contains("isVisible = false"))
    }

    @Test fun drawingUsesOnlyAttachedRowsAndSharedCardTokens() {
        val decoration = source("java/io/nekohasekai/sagernet/widget/SettingsCardDecoration.kt")
        val draw = decoration.substringAfter("override fun onDraw(")
        assertTrue(draw.contains("parent.childCount"))
        assertTrue(draw.contains("parent.getChildAt(i)"))
        assertFalse(draw.contains("0 until adapter.itemCount"))
        assertFalse(draw.contains("adapter.getItem("))
        assertFalse(draw.contains("buildIndex("))
        assertFalse(draw.contains("preferenceCount"))
        assertTrue(decoration.contains("R.attr.colorLayerHigh"))
        assertTrue(decoration.contains("R.attr.materialCardViewStyle"))
        assertTrue(decoration.contains("R.dimen.card_corner_radius"))
        assertTrue(decoration.contains("R.dimen.card_stroke_width"))
        assertFalse(decoration.contains("colorLayerStroke"))
    }

    @Test fun dedicatedHeadingHasNoLegacyMarginOrReservedIconFrame() {
        val layout = source("res/layout/preference_settings_card_heading.xml")
        assertTrue(layout.contains("android:paddingStart=\"@dimen/settings_card_heading_inset\""))
        assertTrue(source("res/values/settings_cards.xml").contains("name=\"settings_card_heading_inset\">16dp"))
        assertTrue(layout.contains("?attr/textAppearanceSubtitle2"))
        assertTrue(layout.contains("@android:id/title"))
        assertFalse(layout.contains("image_frame"))
        assertFalse(layout.contains("layout_marginTop"))
        assertFalse(layout.contains("72dp"))
    }
}
