package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.LatencyTestOverlay
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import io.nekohasekai.sagernet.widget.LatencyTestStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class LatencyTestOverlayTest {
    private fun host(): Pair<FrameLayout, LatencyTestOverlay> {
        val c = RuntimeEnvironment.getApplication()
        val root = FrameLayout(c)
        val edge = GroupListEdgeLayout(c).apply { id = R.id.group_list_foreground }
        val pager = View(c).apply { id = R.id.group_pager }
        edge.addView(pager, FrameLayout.LayoutParams(360, 600))
        root.addView(edge, FrameLayout.LayoutParams(360, 600))
        val overlay = LatencyTestOverlay(root) {}
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 600)
        return root to overlay
    }

    @Test fun runningDoesNotChangePagerPaddingAndReportsCounters() {
        val (root, overlay) = host()
        val pager = root.findViewById<View>(R.id.group_pager)
        val padding = intArrayOf(pager.paddingLeft, pager.paddingTop, pager.paddingRight, pager.paddingBottom)
        overlay.running(10, 52, 3)
        val strip = overlay.stripForTests
        assertEquals(LatencyTestStrip.State.RUNNING, strip.state)
        assertEquals(10, strip.displayedDone)
        assertEquals(52, strip.displayedTotal)
        assertEquals(3, strip.displayedFailed)
        assertEquals(padding.toList(), listOf(pager.paddingLeft, pager.paddingTop, pager.paddingRight, pager.paddingBottom))
        overlay.release()
    }

    @Test fun interruptedAndEmptyClearThePreviousResult() {
        val (_, overlay) = host()
        overlay.running(8, 12, 5)
        overlay.completed(3, 5, interrupted = true)
        assertTrue(overlay.stripForTests.contentDescription.toString().contains("测试中断"))
        overlay.empty("没有未测节点")
        assertEquals(LatencyTestStrip.State.EMPTY, overlay.stripForTests.state)
        assertEquals("没有未测节点", overlay.stripForTests.displayedMessage)
        assertEquals(0, overlay.stripForTests.displayedAvailable)
        assertEquals(0, overlay.stripForTests.displayedFailed)
        overlay.release()
    }

    @Test fun releaseDetachesStripAndIsIdempotent() {
        val (root, overlay) = host()
        overlay.running(1, 1, 0)
        overlay.release(); overlay.release()
        assertEquals(null, root.findViewById<LatencyTestStrip>(R.id.latency_test_strip))
        assertFalse(overlay.stripForTests.isRunning)
    }
}
