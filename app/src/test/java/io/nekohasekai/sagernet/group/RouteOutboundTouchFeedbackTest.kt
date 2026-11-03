package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RouteOutboundTouchFeedbackTest {
    private class Fixture(night: Boolean = false) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val environment = QuickPopupTestEnvironment()
        val context = ContextThemeWrapper(activity.createConfigurationContext(
            Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }), R.style.Theme_SagerNet_Blue)
        val fragment = RouteFragment()
        private val adapter = environment.allocate(RouteFragment.RuleAdapter::class.java)
        val binding = LayoutRouteItemBinding.inflate(android.view.LayoutInflater.from(context))
        val holder: RouteFragment.RuleAdapter.RuleHolder

        init {
            environment.attach(fragment, context)
            // Avoid RuleAdapter's unrelated async initial load. Bind and click the real production holder.
            ReflectionHelpers.setField(adapter, "this\$0", fragment)
            holder = adapter.RuleHolder(binding)
            activity.setContentView(FrameLayout(context).apply { addView(binding.root) })
        }

        fun bind(id: Long, outbound: Long) {
            holder.bind(RuleEntity(id = id, name = "Rule $id", outbound = outbound))
            val content = activity.findViewById<View>(android.R.id.content)
            content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 320, 640)
        }

        fun popup() = ReflectionHelpers.getField<ListPopupWindow?>(fragment, "outboundPopup")

        override fun close() {
            popup()?.dismiss()
            controller.pause().stop().destroy()
            environment.close()
        }
    }

    private fun assertThemeRipple(f: Fixture) {
        val attributes = f.context.obtainStyledAttributes(intArrayOf(
            androidx.appcompat.R.attr.selectableItemBackgroundBorderless))
        val resource = try { attributes.getResourceId(0, 0) } finally { attributes.recycle() }
        assertNotEquals(0, resource)
        assertNotEquals(android.R.drawable.list_selector_background, resource)
        val expected = ContextCompat.getDrawable(f.context, resource)!!
        val outbound = f.binding.routeOutbound.background
        val pencil = f.binding.edit.background
        assertTrue(expected is RippleDrawable)
        assertTrue("The outbound press must be the same borderless ripple as the pencil", outbound is RippleDrawable)
        assertEquals(pencil.javaClass, outbound.javaClass)
        assertEquals(expected.javaClass, outbound.javaClass)
        // Borderless ripples contain no rectangular content or mask layers.
        assertEquals((pencil as RippleDrawable).numberOfLayers, (outbound as RippleDrawable).numberOfLayers)
        assertEquals(0, outbound.numberOfLayers)
        val old = ContextCompat.getDrawable(f.context, android.R.drawable.list_selector_background)!!
        assertNotEquals(old.javaClass, outbound.javaClass)
    }

    @Test fun inflatedAndRecycledRowsAlwaysKeepPencilRippleAndOutboundColors() {
        for (night in listOf(false, true)) Fixture(night).use { f ->
            assertThemeRipple(f) // XML already correct, before any bind.
            val outbounds = listOf(0L, -1L, -2L, 987654L, 0L)
            val colors = listOf(R.color.color_route_proxy, R.color.color_route_direct,
                R.color.color_route_block, R.color.color_route_config, R.color.color_route_proxy)
            outbounds.forEachIndexed { index, outbound ->
                f.bind(501L + index, outbound)
                assertThemeRipple(f)
                assertEquals(ContextCompat.getColor(f.context, colors[index]), f.binding.routeOutbound.currentTextColor)
                assertTrue(f.binding.routeOutbound.isClickable)
                assertTrue(f.binding.routeOutbound.isFocusable)
                assertTrue(f.binding.routeOutbound.height >= (40 * f.context.resources.displayMetrics.density).toInt())
                assertTrue(f.binding.edit.height >= (48 * f.context.resources.displayMetrics.density).toInt())
                assertFalse(f.binding.root.isClickable)
                assertFalse(f.binding.root.isFocusable)
            }
        }
    }

    @Test fun labelUsesLatestStableRuleIdAndCancellationNeverLaunchesEditorOrSaves() {
        Fixture().use { f ->
            val first = RuleEntity(id = 41L, name = "first", outbound = 0L)
            val second = RuleEntity(id = 919L, name = "second", outbound = -2L)
            f.environment.database.rulesDao().insert(listOf(first, second))
            f.bind(first.id, first.outbound)
            // Recycle to a non-position ID, as happens after a rule reorder/update.
            f.bind(second.id, second.outbound)
            assertTrue(f.binding.routeOutbound.performClick())
            val popup = f.popup()!!
            assertTrue(popup.isShowing)
            assertEquals(2, popup.listView!!.selectedItemPosition)
            assertNull(shadowOf(f.activity).nextStartedActivity)
            popup.dismiss()
            assertEquals(first, f.environment.database.rulesDao().getById(first.id))
            assertEquals(second, f.environment.database.rulesDao().getById(second.id))

            // A cancelled press must not open the quick menu or accidentally bubble to editing.
            val label = f.binding.routeOutbound
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 4f, 4f, 0)
            val cancel = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_CANCEL, 4f, 4f, 0)
            try { label.dispatchTouchEvent(down); label.dispatchTouchEvent(cancel) }
            finally { down.recycle(); cancel.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(popup.isShowing)
            assertFalse(label.isPressed)
            assertNull(shadowOf(f.activity).nextStartedActivity)

            // Editing remains available only on the pencil, using the rebound stable ID.
            assertTrue(f.binding.edit.performClick())
            val intent = shadowOf(f.activity).nextStartedActivity
            assertEquals(RouteSettingsActivity::class.java.name, intent.component!!.className)
            assertEquals(second.id, intent.getLongExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, 0L))
        }
    }
}
