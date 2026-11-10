package io.nekohasekai.sagernet.ui

import android.app.Application
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.TagGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TagGroupUiTest {
    private val tags = listOf(TagGroup(1L, "花云", 1L), TagGroup(2L, "工 作", 2L))

    @Test fun trimNonemptyDuplicateAndSelfEditPolicy() {
        assertEquals(R.string.tag_name_empty, TagGroupNames.error("", tags))
        assertEquals(R.string.tag_name_empty, TagGroupNames.error(" \t\n ", tags))
        assertEquals(R.string.tag_name_exists, TagGroupNames.error("  花云  ", tags))
        assertEquals(R.string.tag_name_exists, TagGroupNames.error("工 作", tags))
        assertNull(TagGroupNames.error(" 花云 ", tags, editingId = 1L))
        assertEquals(R.string.tag_name_exists, TagGroupNames.error(" 花云 ", tags, editingId = 2L))
        assertNull("internal whitespace is significant", TagGroupNames.error("工  作", tags))
        assertNull(TagGroupNames.error("工作", tags))
        assertNull("case is not folded", TagGroupNames.error("a", listOf(TagGroup(3, "A"))))
        assertNull("undo may restore another same-name ID; self unchanged remains valid",
            TagGroupNames.error(" 花云 ", listOf(TagGroup(1, "花云"), TagGroup(7, "花云")), 1L))
    }

    class DialogActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_SagerNet_Blue)
            super.onCreate(savedInstanceState)
        }
    }

    @Test fun emptySaveRetainsDialogInputAndFocusAndCancelHasNoWrite() {
        val controller = Robolectric.buildActivity(DialogActivity::class.java).setup()
        val activity = controller.get()
        val dialog = TagGroupNameDialogFragment.newInstance()
        dialog.show(activity.supportFragmentManager, "test_name")
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        val alert = dialog.requireDialog() as AlertDialog
        val input = alert.findViewById<EditText>(android.R.id.edit)!!
        assertTrue(input.hasFocus())
        assertNotNull("reuse themed Neko underlined input", input.background)
        input.setText("   ")
        alert.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(alert.isShowing)
        assertEquals("   ", input.text.toString())
        assertNotNull(input.error)
        assertTrue(input.hasFocus())
        alert.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(alert.isShowing)
        controller.pause().stop().destroy()
    }

    @Test fun nameDialogUsesSavedTextAndEditingTitle() {
        val controller = Robolectric.buildActivity(DialogActivity::class.java).setup()
        val activity = controller.get()
        val dialog = TagGroupNameDialogFragment.newInstance(1L, "花云")
        dialog.show(activity.supportFragmentManager, "test_name")
        activity.supportFragmentManager.executePendingTransactions()
        val input = (dialog.requireDialog() as AlertDialog).findViewById<EditText>(android.R.id.edit)!!
        assertEquals("花云", input.text.toString())
        input.setText("  输入保留  ")
        val saved = Bundle()
        dialog.onSaveInstanceState(saved)
        assertEquals("  输入保留  ", saved.getString("tag_name_text"))
        assertEquals(1L, dialog.arguments!!.getLong("tag_name_id"))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun materialCardsUseThreeFullRowsInBothThemesAndCountBodyClick() {
        for (night in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            val configuration = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_SagerNet_Blue)
            val card = LayoutInflater.from(context).inflate(R.layout.layout_tag_group_item, null) as MaterialCardView
            val rows = card.getChildAt(0) as LinearLayout
            assertEquals(3, rows.childCount)
            val spacer = rows.getChildAt(1)
            assertEquals(R.id.tag_card_spacer, spacer.id)
            assertFalse(spacer is ViewGroup)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, spacer.layoutParams.width)
            assertTrue(spacer.layoutParams.height > 0)
            val name = card.findViewById<TextView>(R.id.tag_name)
            val count = card.findViewById<TextView>(R.id.tag_count)
            name.text = "超长标签名称".repeat(12)
            assertEquals(1, name.maxLines)
            assertEquals(android.text.TextUtils.TruncateAt.END, name.ellipsize)
            count.text = context.getString(R.string.tag_count, 0)
            assertTrue(count.text.toString().startsWith("0"))
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, count.layoutParams.width)
            assertFalse("count row must delegate to body", count.isClickable)
            assertTrue(card.findViewById<View>(R.id.tag_edit).isClickable)
            assertTrue(card.findViewById<View>(R.id.tag_delete).isClickable)
            assertTrue(card.isClickable)
            assertNotEquals(0, card.cardBackgroundColor.defaultColor)
        }
    }

    private fun source(path: String): String = sequenceOf(File("src/main/$path"), File("app/src/main/$path"))
        .first { it.exists() }.readText()

    @Test fun navigationAndAsyncLifecycleContract() {
        val list = source("java/io/nekohasekai/sagernet/ui/TagGroupFragment.kt")
        assertTrue(list.contains("viewLifecycleOwner.lifecycleScope"))
        assertTrue(list.contains("withContext(Dispatchers.IO)"))
        assertTrue(list.contains("putLong(ARG_TAG_ID, tag.id)"))
        assertTrue(list.contains("putBoolean(ARG_TAG_EDIT, edit)"))
        assertTrue(list.contains("displayFragmentWithId(R.id.nav_group, forward = false)"))
        assertTrue(list.contains("ignoreClickUntil = SystemClock.uptimeMillis() + 200L"))
        assertTrue(list.contains("TagGroupStore.reorder(ids)"))
        assertTrue(list.contains("record::restore"))
        assertTrue(list.contains("filterNot(PendingGroupDeletes::contains)"))
        assertTrue(list.contains("combine(changes, PendingGroupDeletes.changes)"))
        assertTrue(list.contains("!bodyGesture"))
        assertFalse(list.contains("UNDO_DURATION_MS"))
        assertFalse(list.contains("tag_undo_countdown"))
        assertFalse(list.contains("undoTimer"))
        assertTrue(list.contains("R.string.tag_restored"))
        assertTrue(list.contains("operationJob?.cancel()"))
        assertTrue(list.contains("viewGeneration++"))
        assertTrue(list.contains("StateRestorationPolicy.PREVENT_WHEN_EMPTY"))
        val nav = source("java/io/nekohasekai/sagernet/ui/TagGroupNavigation.kt")
        assertTrue(nav.contains("WeakHashMap<MainActivity, Entry>"))
        assertTrue(nav.contains("WeakReference<TagGroupFragment>"))
        assertTrue(nav.contains("findFragmentByTag(FRAGMENT_TAG)"))
        assertTrue(nav.contains("fun open(activity: MainActivity, forward: Boolean = true)"))
        assertFalse(nav.contains("WeakHashMap<MainActivity, TagGroupFragment>"))
    }
}
