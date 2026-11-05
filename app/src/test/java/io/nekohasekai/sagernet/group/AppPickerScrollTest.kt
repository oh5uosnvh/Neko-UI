package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.AppPickerHeader
import io.nekohasekai.sagernet.widget.TitleBarLayout
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
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppPickerScrollTest {
    @Test fun realPickerHeaderScrollsWithCardsAndSurvivesFilteringRecyclingAndEmptyResults() {
        for (resource in listOf(R.layout.layout_apps, R.layout.layout_app_list)) {
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
            controller.setup().visible()
            try {
                val root = LayoutInflater.from(activity).inflate(resource, null) as TitleBarLayout
                val filters = root.findViewById<LinearLayout>(R.id.app_list_header)
                val search = root.findViewById<EditText>(R.id.search)
                val loading = root.findViewById<View>(R.id.loading)
                val placeholder = root.findViewById<View>(R.id.app_placeholder)
                val list = root.findViewById<RecyclerView>(R.id.list)
                val header = AppPickerHeader(filters)
                header.placeStatus(loading)
                header.placeStatus(placeholder)
                loading.visibility = View.GONE
                var count = 90
                var changes = 0
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes++ }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
                val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = count + 1
                    override fun getItemViewType(position: Int) = if (position == 0) AppPickerHeader.VIEW_TYPE else 0
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                        if (viewType == AppPickerHeader.VIEW_TYPE) header.createHolder()
                        else object : RecyclerView.ViewHolder(TextView(activity).apply {
                            layoutParams = RecyclerView.LayoutParams(-1, 90)
                        }) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        if (position != 0) (holder.itemView as TextView).text = "App $position"
                    }
                }
                list.layoutManager = LinearLayoutManager(activity)
                list.itemAnimator = DefaultItemAnimator()
                list.adapter = adapter
                list.visibility = View.VISIBLE
                activity.setContentView(root)
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 600, 1000)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                }
                layout()
                search.setText("keep me")
                assertSame(list, filters.parent)
                val initialY = filters.y
                list.scrollBy(0, 160)
                assertTrue(filters.y < initialY)
                repeat(4) {
                    list.scrollToPosition(count)
                    layout()
                    count = if (it % 2 == 0) 45 else 90
                    adapter.notifyDataSetChanged()
                    layout()
                    list.scrollToPosition(0)
                    layout()
                    assertSame(filters, list.findViewHolderForAdapterPosition(0)!!.itemView)
                    assertEquals("keep me", search.text.toString())
                }
                count = 0
                adapter.notifyDataSetChanged()
                layout()
                assertEquals(1, adapter.itemCount)
                assertSame(list, filters.parent)
                assertEquals(1, changes)
                assertSame(filters, loading.parent)
                assertSame(filters, placeholder.parent)
                assertEquals("", AppPickerHeader.section(0, 0) { error("header isn't an app") })
                assertEquals("", AppPickerHeader.section(5, 2) { error("stale fast scroll") })
                assertEquals("B", AppPickerHeader.section(2, 3) { listOf("A", "B", "C")[it] })
            } finally { controller.pause().stop().destroy() }
        }
    }
}
