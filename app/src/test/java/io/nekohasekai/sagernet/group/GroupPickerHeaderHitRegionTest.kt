package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupPickerHeader
import io.nekohasekai.sagernet.ui.GroupPickerNavigation
import io.nekohasekai.sagernet.ui.GroupPickerTagScopeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Real DOWN/UP dispatch catches weighted child views swallowing their parent's clicks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupPickerHeaderHitRegionTest {
    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 30L)) {
            val e = MotionEvent.obtain(now, now + offset, action, x, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun entireLockedCapsuleReturnsExceptIndependentRedMarker() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet_Blue) }
        controller.setup()
        var backs = 0
        var markerTaps = 0
        val header = GroupPickerHeader(activity, Color.WHITE, { ColorDrawable(Color.DKGRAY) },
            {}, {}, { backs++ }, { 71L }, { markerTaps++ })
        try {
            activity.setContentView(header)
            header.bind(GroupPickerNavigation.Page.Members(71L), "良心云")
            val w = (360 * activity.resources.displayMetrics.density).toInt()
            val h = (64 * activity.resources.displayMetrics.density).toInt()
            header.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            header.layout(0, 0, w, h)
            val row = ReflectionHelpers.getField<View>(header, "backRow")
            val marker = ReflectionHelpers.getField<TextView>(header, "lockIndicator")
            val title = ReflectionHelpers.getField<TextView>(header, "title")
            assertFalse("Decorative title must not consume DOWN", title.isClickable)
            assertFalse("Decorative title must not consume short taps", title.isLongClickable)
            assertEquals(View.VISIBLE, marker.visibility)
            val points = listOf(row.width * 0.08f, row.width * 0.35f,
                row.width * 0.6f, marker.left - 2f)
            points.forEachIndexed { index, x ->
                tap(row, x, row.height / 2f)
                assertEquals("Back at x=$x", index + 1, backs)
            }
            tap(row, (marker.left + marker.right) / 2f, (marker.top + marker.bottom) / 2f)
            assertEquals("Red marker must not return", points.size, backs)
            assertEquals(1, markerTaps)
            assertTrue(row.performClick())
            assertEquals(points.size + 1, backs)
        } finally {
            GroupPickerTagScopeBridge.onLongPress = null
            controller.pause().stop().destroy()
        }
    }
}
