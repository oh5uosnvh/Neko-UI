package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.ui.GroupPageRequests
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupPageAnimationTest {
    @Test fun neighbouringClickIssuesRealSettlingScrollAndQueuedLatestKeepsAdapter() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val pager = ViewPager2(controller.get())
        var fullRefreshes = 0
        val pages = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 6
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        pages.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fullRefreshes++ }
        })
        pager.adapter = pages
        val states = ArrayList<Int>()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) { states.add(state) }
        })
        controller.get().setContentView(pager)
        pager.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        pager.layout(0, 0, 600, 800)
        val requests = GroupPageRequests()
        val ids = (1L..6L).toList()
        try {
            requests.request(2)
            assertTrue(requests.applyWhenIdle(pager, ids))
            assertEquals(ViewPager2.SCROLL_STATE_SETTLING, pager.scrollState)
            repeat(100) {
                requests.request(if (it == 99) 6 else 3 + it % 3L)
                assertFalse(requests.applyWhenIdle(pager, ids))
            }
            repeat(80) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
            assertTrue(requests.applyWhenIdle(pager, ids))
            repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertEquals(5, pager.currentItem)
            assertSame(pages, pager.adapter)
            assertEquals(0, fullRefreshes)
            assertEquals(2, states.count { it == ViewPager2.SCROLL_STATE_SETTLING })
        } finally {
            pager.adapter = null
            controller.pause().stop().destroy()
        }
    }
}
