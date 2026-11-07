package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.FlMenu
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** Real LEGACY PopupWindow/menu interaction tests; no source-only contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FlMenuRealBehaviorTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private fun frames(count: Int = 24) {
        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    class TestActivity : AppCompatActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_SagerNet_Blue)
            super.onCreate(state)
        }
    }

    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup().visible()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val anchor = View(activity)

        init {
            activity.setContentView(host)
            host.addView(anchor, FrameLayout.LayoutParams(48, 48))
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 640)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue(anchor.isAttachedToWindow)
            assertNotNull(anchor.windowToken)
        }

        fun inflate(resource: Int): Menu = MenuBuilder(activity).also {
            activity.menuInflater.inflate(resource, it)
        }

        fun open(menu: Menu, onItem: (android.view.MenuItem) -> Boolean = { true }): android.widget.PopupWindow {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val popup = FlMenu.show(anchor, menu, onItem)
            assertTrue(popup.isShowing)
            // WindowManager attachment is asynchronous; exercise a real attached
            // PopupWindow before advancing its first content layout.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val content = popup.contentView
            content.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, popup.width, popup.height)
            assertTrue(content.isAttachedToWindow)
            return popup
        }

        override fun close() {
            controller.pause().stop().destroy()
        }
    }

    private fun View.findDescription(value: CharSequence): View? {
        if (contentDescription == value) return this
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findDescription(value)?.let { return it }
        }
        return null
    }

    @Test fun inflatedAddProfileNestedPageBackAndLeafCallbackOnce() {
        Fixture().use { f ->
            val menu = f.inflate(R.menu.add_profile_menu)
            var calls = 0
            assertTrue(menu.findItem(R.id.action_add).hasSubMenu())
            val popup = f.open(menu.findItem(R.id.action_add).subMenu!!) { calls++; true }
            val manual = popup.contentView.findDescription(f.activity.getString(R.string.add_profile_methods_manual_settings))
                ?: error("manual submenu row missing")
            assertTrue(manual.performClick())
            frames()
            val leaf = popup.contentView.findDescription(f.activity.getString(R.string.action_socks))
                ?: error("manual leaf missing")
            assertTrue(leaf.performClick())
            frames()
            assertEquals(1, calls)
            assertFalse(popup.isShowing)
        }
    }

    @Test fun profileShareNestedPageBackAndIndependentItemListenerAreDeliveredOnce() {
        Fixture().use { f ->
            val menu = f.inflate(R.menu.profile_share_menu)
            var listenerCalls = 0
            var callbackCalls = 0
            val leaf = menu.findItem(R.id.action_standard_qr)
            leaf.setOnMenuItemClickListener {
                listenerCalls++
                false // let the popup's business callback receive the same leaf
            }
            val popup = f.open(menu) { item ->
                assertEquals(R.id.action_standard_qr, item.itemId)
                callbackCalls++
                true
            }
            val qr = popup.contentView.findDescription(f.activity.getString(R.string.share_qr_nfc))
                ?: error("QR submenu row missing")
            assertTrue(qr.performClick())
            frames()
            val back = popup.contentView.findDescription(f.activity.getString(R.string.share_qr_nfc))
                ?: error("submenu back row missing")
            assertTrue(back.performClick())
            frames()
            assertTrue(popup.isShowing)
            val qrAgain = popup.contentView.findDescription(f.activity.getString(R.string.share_qr_nfc))
                ?: error("QR row missing after back")
            assertTrue(qrAgain.performClick())
            frames()
            val standard = popup.contentView.findDescription(f.activity.getString(R.string.standard))
                ?: error("standard leaf missing")
            assertTrue(standard.performClick())
            frames()
            assertEquals(1, listenerCalls)
            assertEquals(1, callbackCalls)
            assertFalse(popup.isShowing)
        }
    }

    @Test fun toolbarOverflowUsesIndependentCardStyleListenerAndClosesAfterExit() {
        Fixture().use { f ->
            val root = f.inflate(R.menu.add_profile_menu)
            val menu = root.findItem(R.id.action_misc).subMenu!!
            var applied = 0
            var fallback = 0
            menu.findItem(R.id.action_card_style_stroke).setOnMenuItemClickListener {
                applied++
                it.isChecked = true
                true
            }
            val popup = f.open(menu) { fallback++; true }
            frames(32)
            val submenu = popup.contentView.findDescription(f.activity.getString(R.string.sort_and_layout))!!
            submenu.performClick()
            frames()
            popup.contentView.findViewById<View>(R.id.action_card_style_stroke).performClick()
            assertEquals(0, applied)
            frames()
            assertEquals(1, applied)
            assertEquals(0, fallback)
            assertTrue(menu.findItem(R.id.action_card_style_stroke).isChecked)
            assertFalse(popup.isShowing)
        }
    }

    @Test fun deepSubmenuBackRevealsStationaryParentWithoutReopeningWindow() {
        Fixture().use { f ->
            val menu = MenuBuilder(f.activity)
            val first = menu.addSubMenu("one")
            val second = first.addSubMenu("two")
            second.add(0, 12345, 0, "leaf")
            var chosen = 0
            val popup = f.open(menu) { chosen++; true }
            frames(32)
            popup.contentView.findDescription("one")!!.performClick(); frames()
            popup.contentView.findDescription("two")!!.performClick(); frames()
            popup.contentView.findDescription("two")!!.performClick(); frames()
            assertTrue(popup.isShowing)
            assertNotNull(popup.contentView.findDescription("one"))
            popup.contentView.findDescription("two")!!.performClick(); frames()
            popup.contentView.findViewById<View>(12345).performClick(); frames()
            assertEquals(1, chosen)
            assertFalse(popup.isShowing)
        }
    }

    @Test fun cancelAndAnchorDetachNeverDeliverPendingLeaf() {
        Fixture().use { f ->
            val menu = f.inflate(R.menu.double_column_item_menu)
            var calls = 0
            val popup = f.open(menu) { calls++; true }
            popup.dismiss()
            frames()
            assertEquals(0, calls)

            val popup2 = f.open(menu) { calls++; true }
            val edit = popup2.contentView.findDescription(f.activity.getString(R.string.edit))
                ?: error("edit row missing")
            assertTrue(edit.performClick())
            f.host.removeView(f.anchor)
            frames()
            assertEquals(0, calls)
            assertFalse(popup2.isShowing)
        }
    }
}
