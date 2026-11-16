package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupSearchBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupSearchBarTest {
    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet_Blue) }
        val parent = activity.layoutInflater.inflate(R.layout.layout_appbar, null) as com.google.android.material.appbar.AppBarLayout
        val toolbar = parent.findViewById<Toolbar>(R.id.toolbar)
        val bar = GroupSearchBar(toolbar, activity)
        var closed = 0
        var confirmed = 0
        var classified = 0
        var lastQuery = ""
        init {
            controller.setup()
            activity.setContentView(parent)
            controller.visible()
            bar.attach({ lastQuery = it }, { classified++ }, { closed++ }, { confirmed++ })
            layout()
        }
        fun layout(width: Int = 360) {
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, parent.measuredHeight)
        }
        override fun close() { bar.detach(); controller.pause().stop().destroy() }
    }

    @Test fun emptyQueryOffersAllGroupsWithoutCrowdingTheInput() {
        Fixture().use { f ->
            val b = f.bar
            b.render("", 4, false, 0, false)
            f.layout(320)
            assertEquals(View.GONE, f.toolbar.visibility)
            assertEquals(b.inputRow, b.row.getChildAt(0))
            assertEquals(b.actionRow, b.row.getChildAt(1))
            assertTrue(b.actionRow.top >= b.inputRow.bottom)
            assertTrue(b.search.width > b.row.width / 2)
            assertEquals(f.activity.getString(R.string.group_classification_all), b.classify.text.toString())
            assertTrue(b.classify.isEnabled)
            assertEquals(View.GONE, b.confirm.visibility)
            b.classify.performClick()
            assertEquals(1, f.classified)
            assertEquals(0, f.confirmed)
            val internalClose = b.search.findViewById<View>(androidx.appcompat.R.id.search_close_btn)
            assertEquals(0, internalClose.layoutParams.width)
            assertEquals(0, internalClose.layoutParams.height)
            b.close.performClick()
            assertEquals(1, f.closed)
        }
    }

    @Test fun oneMatchHasExplicitClassifyAndNoMatchesCannotBeSubmitted() {
        Fixture().use { f ->
            val b = f.bar
            b.search.setQuery("饭", false)
            assertEquals("饭", f.lastQuery)
            b.render("饭", 1, false, 0, false)
            assertEquals(f.activity.getString(R.string.group_classify), b.classify.text.toString())
            assertEquals(View.VISIBLE, b.classify.visibility)
            assertTrue(b.classify.isEnabled)
            b.classify.performClick()
            assertEquals(1, f.classified)
            b.render("无匹配", 0, false, 0, false)
            assertEquals(f.activity.getString(R.string.group_classification_empty), b.status.text.toString())
            assertFalse(b.classify.isEnabled)
            b.classify.performClick()
            assertEquals(1, f.classified)
            b.render("", 0, false, 0, false)
            assertFalse(b.classify.isEnabled)
        }
    }

    @Test fun customSelectionRequiresSelectionAndItsOwnConfirmation() {
        Fixture().use { f ->
            val b = f.bar
            b.render("饭", 3, true, 0, false)
            assertEquals(View.GONE, b.classify.visibility)
            assertEquals(View.VISIBLE, b.confirm.visibility)
            assertFalse(b.confirm.isEnabled)
            b.confirm.performClick()
            assertEquals(0, f.confirmed)
            b.render("饭", 3, true, 2, false)
            assertTrue(b.confirm.isEnabled)
            b.confirm.performClick()
            assertEquals(1, f.confirmed)
            assertEquals(0, f.classified)
        }
    }

    @Test fun savingDisablesRepeatedSavesQueryEditsAndCloseUntilCompletion() {
        Fixture().use { f ->
            val b = f.bar
            b.render("饭", 3, true, 2, false, saving = true)
            b.confirm.performClick()
            b.classify.performClick()
            b.close.performClick()
            b.search.setQuery("changed", false)
            assertEquals("", f.lastQuery)
            assertEquals(0, f.confirmed + f.classified + f.closed)
            assertFalse(b.search.findViewById<EditText>(androidx.appcompat.R.id.search_src_text).isEnabled)
            b.render("饭", 3, true, 2, false)
            b.confirm.performClick()
            b.close.performClick()
            assertEquals(1, f.confirmed)
            assertEquals(1, f.closed)
        }
    }

    @Test fun rapidQueryChangesAndDetachLeaveNoStaleActionsAndRestoreToolbar() {
        Fixture().use { f ->
            val b = f.bar
            repeat(10) {
                b.render("饭", 3, false, 0)
                b.render("无匹配", 0, false, 0)
                b.render("", 4, false, 0)
            }
            assertTrue(b.classify.isEnabled)
            assertEquals(View.GONE, b.confirm.visibility)
            b.detach()
            assertNull(b.row.parent)
            assertEquals(View.VISIBLE, f.toolbar.visibility)
        }
    }

    @Test fun textAndHintUseTheSameThemeInkAsConfigurationSearch() {
        Fixture().use { f ->
            val text = f.bar.search.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            val attrs = f.activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val expected = attrs.getColor(0, -1)
            attrs.recycle()
            assertEquals(expected, text.currentTextColor)
            assertEquals(expected, text.currentHintTextColor)
        }
    }
}
