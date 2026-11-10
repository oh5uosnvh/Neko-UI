package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupSearchBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

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
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 360, 640)
        }
        fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(320)); layout() }
        override fun close() { bar.detach(); controller.pause().stop().destroy() }
    }

    @Test fun emptySearchHasOneCloseAndAnInvisibleButMeasuredFarRightConfirm() {
        Fixture().use { f ->
            val b = f.bar
            b.render("", 0, false, 0, false)
            f.layout()
            assertEquals(View.GONE, f.toolbar.visibility)
            assertEquals(listOf(b.search, b.classifySlot, b.close, b.confirm),
                (0 until b.row.childCount).map { b.row.getChildAt(it) })
            assertEquals(View.INVISIBLE, b.confirm.visibility)
            assertTrue(b.confirm.width > 0)
            assertEquals(b.close.right, b.confirm.left)
            assertEquals(View.GONE, b.classifySlot.visibility)
            assertFalse(b.confirm.isEnabled)
            b.confirm.performClick()
            assertEquals(0, f.confirmed)
            b.search.setQuery("饭", false)
            assertEquals("饭", f.lastQuery)
            val internalClose = b.search.findViewById<View>(androidx.appcompat.R.id.search_close_btn)
            assertEquals(0, internalClose.layoutParams.width)
            assertEquals(0, internalClose.layoutParams.height)
            b.close.performClick()
            assertEquals(1, f.closed)
        }
    }

    @Test fun classificationOnlyAfterTwoMatchesAndExpandsLeftWithoutMovingXOrTick() {
        Fixture().use { f ->
            val b = f.bar
            val x = b.close.left
            val tick = b.confirm.left
            b.render("饭", 1, false, 0, false)
            f.layout()
            assertEquals(View.GONE, b.classifySlot.visibility)
            b.render("饭", 2, false, 0)
            f.settle()
            assertEquals(View.VISIBLE, b.classifySlot.visibility)
            assertTrue(b.classifySlot.width > 0)
            assertTrue(b.classifySlot.left < b.close.left)
            assertEquals(x, b.close.left)
            assertEquals(tick, b.confirm.left)
            assertEquals(0f, b.classify.translationX, .01f)
            b.classify.performClick()
            assertEquals(1, f.classified)
            b.render("饭", 2, true, 0, false)
            f.layout()
            assertEquals(View.GONE, b.classifySlot.visibility)
            assertEquals(View.VISIBLE, b.confirm.visibility)
            assertFalse(b.confirm.isEnabled)
            b.render("饭", 2, true, 1, false)
            b.confirm.performClick()
            assertEquals(1, f.confirmed)
            assertEquals(tick, b.confirm.left)
        }
    }

    @Test fun rapidQueryChangesLeaveNoStalePillAndDetachRestoresToolbar() {
        Fixture().use { f ->
            val b = f.bar
            b.render("饭", 3, false, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
            b.render("无匹配", 0, false, 0)
            b.render("饭", 3, false, 0)
            b.render("", 0, false, 0)
            f.settle()
            assertEquals(View.GONE, b.classifySlot.visibility)
            assertEquals(0, b.classifySlot.layoutParams.width)
            assertEquals(View.INVISIBLE, b.confirm.visibility)
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
