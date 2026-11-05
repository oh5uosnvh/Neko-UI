package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.ui.GroupFragment
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

/** Real GroupHolder/card/visual feedback, without attaching the VPN MainActivity or reading a DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupJumpHighlightTest {
    private class Fixture : AutoCloseable {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
        val context = activity.get().apply { setTheme(R.style.Theme_SagerNet_Blue) }
        val fragment = GroupFragment()
        val list = RecyclerView(context)
        val holders = ArrayList<GroupFragment.GroupHolder>()
        var clicks = 0
        init {
            activity.setup()
            list.layoutManager = LinearLayoutManager(context)
            list.itemAnimator = null
            list.adapter = object : RecyclerView.Adapter<GroupFragment.GroupHolder>() {
                init { setHasStableIds(true) }
                override fun getItemCount() = 4
                override fun getItemId(position: Int) = 101L + position
                override fun onCreateViewHolder(parent: ViewGroup, type: Int): GroupFragment.GroupHolder {
                    val binding = LayoutGroupItemBinding.inflate(context.layoutInflater, parent, false)
                    return fragment.GroupHolder(binding).also { holders.add(it) }
                }
                override fun onBindViewHolder(holder: GroupFragment.GroupHolder, position: Int) {
                    // Do not call business bind(): it reads profile counts asynchronously.
                    holder.proxyGroup = ProxyGroup(id = getItemId(position))
                    holder.itemView.setOnClickListener { clicks++ }
                    holder.editButton.setOnClickListener { clicks++ }
                    holder.updateButton.setOnClickListener { clicks++ }
                }
            }
            fragment.groupListView = list
            fragment.toolbar = Toolbar(context)
            context.setContentView(list)
            activity.visible()
            list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 320, 640)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        fun holder(id: Long) = list.findViewHolderForItemId(id) as GroupFragment.GroupHolder
        fun card(id: Long) = holder(id).itemView as MaterialCardView
        fun highlight(id: Long) {
            GroupFragment::class.java.getDeclaredMethod("highlightGroup", java.lang.Long.TYPE).apply {
                isAccessible = true
            }.invoke(fragment, id)
        }
        override fun close() {
            fragment.cancelGroupJump()
            list.adapter = null
            activity.pause().stop().destroy()
        }
    }

    @Test fun exactlyOneVisualPulseRestoresOriginalColorStateListWithoutCallingBusinessClicks() {
        Fixture().use { f ->
            val first = f.card(101)
            val second = f.card(102)
            val original = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(Color.CYAN, Color.rgb(80, 100, 120)))
            first.setCardBackgroundColor(original)
            val secondOriginal = second.cardBackgroundColor
            f.highlight(101)
            assertNotEquals(original.defaultColor, first.cardBackgroundColor.defaultColor)
            assertEquals(0, f.clicks)
            assertFalse(first.isPressed)
            assertFalse(first.isSelected)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.highlight(102)
            assertSame(original, first.cardBackgroundColor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(219))
            assertNotEquals(secondOriginal.defaultColor, second.cardBackgroundColor.defaultColor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertSame(secondOriginal, second.cardBackgroundColor)
            assertEquals(0, f.clicks)
        }
    }

    @Test @Config(qualifiers = "night")
    fun nightCardRestoresTheSameOriginalSurfaceAfterPulse() {
        Fixture().use { f ->
            val card = f.card(101)
            val original = card.cardBackgroundColor
            f.highlight(101)
            assertNotEquals(original.defaultColor, card.cardBackgroundColor.defaultColor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
            assertSame(original, card.cardBackgroundColor)
            assertEquals(0, f.clicks)
        }
    }

    @Test fun recycledHolderRestoresItsOriginalColorBeforeItCanBeRebound() {
        Fixture().use { f ->
            val holder = f.holder(101)
            val card = holder.itemView as MaterialCardView
            val original = card.cardBackgroundColor
            f.highlight(101)
            // Only construct the real adapter's recycling hook; suppress its async initial reload.
            GroupFragment::class.java.getDeclaredField("preparedGroups").apply { isAccessible = true }
                .set(f.fragment, emptyList<ProxyGroup>())
            val adapter = f.fragment.GroupAdapter()
            adapter.onViewRecycled(holder)
            assertSame(original, card.cardBackgroundColor)
            val rebound = ColorStateList.valueOf(Color.GREEN)
            card.setCardBackgroundColor(rebound)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertSame(rebound, card.cardBackgroundColor)
            assertEquals(0, f.clicks)
        }
    }

    @Test fun wrongStableIdAndCancellationCannotColorAReboundCardOrLeaveDelayedReset() {
        Fixture().use { f ->
            val card = f.card(101)
            val original = card.cardBackgroundColor
            f.holder(101).proxyGroup = ProxyGroup(id = 999)
            f.highlight(101)
            assertSame(original, card.cardBackgroundColor)
            f.holder(101).proxyGroup = ProxyGroup(id = 101)
            f.highlight(101)
            f.fragment.cancelGroupJump()
            assertSame(original, card.cardBackgroundColor)
            val rebound = ColorStateList.valueOf(Color.MAGENTA)
            card.setCardBackgroundColor(rebound)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertSame(rebound, card.cardBackgroundColor)
            assertEquals(0, f.clicks)
        }
    }
}
