package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ExactScrollRecyclerView
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real RecyclerViews, measured unequal rows, and host touch dispatch; no source-string tests. */
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class GroupFixedScrollbarTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    @After fun restoreFrames(){ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))}
    private fun reveal(host: GroupListEdgeLayout) {
        host.fixedScrollbar.onScrollActivity()
        repeat(11){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))}
    }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun measure(view: View, w: Int = 360, h: Int = 640) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }
    private class Rows(private val rows: Int, private val variable: Boolean) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.layoutParams = RecyclerView.LayoutParams(-1, if (variable && position % 3 == 1) 137 else 53)
        }
    }
    private fun list(grid: Boolean = false, variable: Boolean = true, count: Int = 300) = ExactScrollRecyclerView(context).apply {
        setPadding(4, 4, 4, 131)
        layoutManager = if (grid) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        adapter = Rows(count, variable)
    }
    private fun host(vararg lists: ExactScrollRecyclerView) = GroupListEdgeLayout(context).apply {
        lists.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)); bindList(it) }
        measure(this)
        reveal(this)
    }
    private fun event(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 10, action, x, y, 0)
    private fun send(host: GroupListEdgeLayout, action: Int, x: Float, y: Float): Boolean {
        val event = event(action, x, y)
        return try { host.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun physicalRightIsInvariantAtPlusAndMinusHalfPageTranslation() {
        val list = list()
        val host = host(list)
        list.scrollBy(0, 900)
        val original = host.fixedScrollbar.geometry()!!
        for (x in listOf(-180f, 0f, 180f)) {
            list.translationX = x
            val moved = host.fixedScrollbar.geometry()!!
            assertEquals(original.track, moved.track)
            assertEquals(original.thumb, moved.thumb)
            assertEquals(360f, moved.track.right, 0f)
        }
    }

    @Test fun onlyMajorVisibleListOwnsOneThumbAndTargetStaysLatchedUntilCancel() {
        val first = list()
        val second = list()
        val host = host(first, second)
        first.translationX = -120f
        second.translationX = 240f
        first.scrollBy(0, 300)
        second.scrollBy(0, 1400)
        val geometry = host.fixedScrollbar.geometry()!!
        assertSame(first, host.fixedScrollbar.activeList)
        val secondOffset = second.computeVerticalScrollOffset()
        val x = geometry.thumb.centerX()
        val y = geometry.thumb.top + 7f
        assertTrue(send(host, MotionEvent.ACTION_DOWN, x, y))
        first.translationX = -240f
        second.translationX = 120f
        assertTrue(send(host, MotionEvent.ACTION_MOVE, x, y + 30f))
        assertSame(first, host.fixedScrollbar.activeList)
        assertEquals(secondOffset, second.computeVerticalScrollOffset())
        assertEquals(geometry.thumb.top + 30f, host.fixedScrollbar.geometry()!!.thumb.top, 0.1f)
        assertTrue(send(host, MotionEvent.ACTION_CANCEL, x, y + 30f))
        assertFalse(host.fixedScrollbar.isDragging)
        host.fixedScrollbar.geometry()
        assertSame(second, host.fixedScrollbar.activeList)
    }

    @Test fun firstMoveUsesActualPixelsWithoutDoubleGrabDeltaForUnequalRowsAndGrid() {
        for (grid in listOf(false, true)) {
            val list = list(grid)
            val host = host(list)
            val g = host.fixedScrollbar.geometry()!!
            val travel = g.track.height() - g.thumb.height()
            val delta = 12f
            val expected = (delta * g.scrollable / travel).toInt()
            val x = g.thumb.centerX()
            val y = g.thumb.top + 11f
            val oldFirst = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            assertTrue(send(host, MotionEvent.ACTION_DOWN, x, y))
            assertTrue(send(host, MotionEvent.ACTION_MOVE, x, y + delta))
            assertEquals(g.thumb.top + delta, host.fixedScrollbar.geometry()!!.thumb.top, 0.1f)
            assertTrue((list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() > oldFirst)
            assertTrue(expected > 0)
            // An unchanged finger produces no second scroll despite new visible row heights.
            val offset = list.computeVerticalScrollOffset()
            send(host, MotionEvent.ACTION_MOVE, x, y + delta)
            assertEquals(offset, list.computeVerticalScrollOffset())
            send(host, MotionEvent.ACTION_UP, x, y + delta)
            assertFalse(host.fixedScrollbar.isDragging)
        }
    }

    @Test fun rtlLongListAndTinyViewportRespectBandAndCurrentBottomInset() {
        val list = list(count = 100000)
        list.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val host = host(list)
        val g = host.fixedScrollbar.geometry()!!
        assertEquals(360f, g.thumb.right, 0f)
        assertTrue(g.track.top >= 56f)
        assertEquals(509f, g.track.bottom, 0f)
        val tabs = View(context).apply { id = R.id.group_tab }
        host.addView(tabs, FrameLayout.LayoutParams(-1, 82))
        measure(host)
        assertTrue(host.fixedScrollbar.geometry()!!.track.top >= tabs.bottom)
        measure(host, 360, 140)
        assertNull(host.fixedScrollbar.geometry())
        // Software path is safe even when no valid thumb/travel exists.
        host.draw(Canvas(Bitmap.createBitmap(360, 140, Bitmap.Config.ARGB_8888)))
    }

    @Test fun lifecycleDetachesListenerAndRestoresNativeModeWithoutRetainingLists() {
        val list = list()
        val host = host(list)
        assertEquals(1, host.fixedScrollbar.registeredListCount)
        val bar = host.fixedScrollbar.geometry()!!.thumb
        send(host, MotionEvent.ACTION_DOWN, bar.centerX(), bar.centerY())
        host.fixedScrollbar.detach()
        assertFalse(host.fixedScrollbar.isDragging)
        assertNull(host.fixedScrollbar.activeList)
        assertEquals(0, host.fixedScrollbar.registeredListCount)
        assertNull(host.fixedScrollbar.geometry())
        host.bindList(list)
        assertEquals(1, host.fixedScrollbar.registeredListCount)
        assertNotNull(host.fixedScrollbar.geometry())
    }

    @Test fun outsideThumbNeverConsumesToolbarOrNormalContentGestures() {
        val host = host(list())
        val g = host.fixedScrollbar.geometry()!!
        val top = event(MotionEvent.ACTION_DOWN, g.thumb.centerX(), 15f)
        val content = event(MotionEvent.ACTION_DOWN, 100f, 300f)
        try {
            assertFalse(host.fixedScrollbar.touch(top))
            assertFalse(host.fixedScrollbar.touch(content))
            assertFalse(host.fixedScrollbar.isDragging)
        } finally { top.recycle(); content.recycle() }
    }
}
