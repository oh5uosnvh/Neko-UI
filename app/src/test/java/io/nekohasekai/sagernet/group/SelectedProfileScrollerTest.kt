package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ui.SelectedProfileScroller
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SelectedProfileScrollerTest {
    @Test fun liveViewportMovesContinuouslyToStableTargetIn500MsForOneAndTwoColumns() {
        for (span in listOf(1, 2)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = controller.get()
            val ids = (1L..200L).toMutableList()
            val list = RecyclerView(activity)
            val manager = if (span == 1) LinearLayoutManager(activity) else GridLayoutManager(activity, span)
            list.layoutManager = manager
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                init { setHasStableIds(true) }
                override fun getItemCount() = ids.size
                override fun getItemId(position: Int) = ids[position]
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : RecyclerView.ViewHolder(
                    TextView(parent.context).apply { layoutParams = RecyclerView.LayoutParams(-1, 80) }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) { (holder.itemView as TextView).text = ids[position].toString() }
            }
            activity.setContentView(list)
            fun frame() {
                list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 360, 600)
                list.viewTreeObserver.dispatchOnPreDraw()
            }
            frame()
            manager.scrollToPositionWithOffset(30 * span, -20)
            frame()
            val start = manager.findFirstVisibleItemPosition()
            val jump = SelectedProfileScroller(list)
            try {
                jump.scrollTo(ids[100 * span.coerceAtMost(1)]) { ids }
                frame()
                val animation = ReflectionHelpers.getField<ValueAnimator>(jump, "animator")
                assertEquals(500L, animation.duration)
                animation.currentPlayTime = 250
                frame()
                val middle = manager.findFirstVisibleItemPosition()
                assertTrue("span=$span start=$start middle=$middle", middle > start && middle < 100)
                animation.currentPlayTime = 500
                frame()
                assertTrue(manager.findFirstVisibleItemPosition() in 98..100)
                assertNotNull(list.findViewHolderForItemId(ids[100]))
            } finally { jump.close(); controller.pause().stop().destroy() }
        }
    }
}
