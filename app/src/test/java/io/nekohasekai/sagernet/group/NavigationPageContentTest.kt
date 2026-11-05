package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.NavigationPageContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationPageContentTest {
    private class Rows(var count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    @Test fun routeDocumentationIsNotADataResultAndEmptyResultStillReleasesReadiness() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val list = RecyclerView(context).apply { id = R.id.route_list }
        val rows = Rows(1)
        list.adapter = rows
        root.addView(list)
        val content = NavigationPageContent(root, R.id.nav_route)
        try {
            assertFalse(content.isReady())
            rows.notifyDataSetChanged() // Empty route database: the one documentation row remains.
            assertTrue(content.isReady())
        } finally { content.dispose() }
    }

    @Test fun groupPreparedDataIsReadyAndDisposedObserverCannotReceiveLateResults() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val list = RecyclerView(context).apply { id = R.id.group_list }
        val rows = Rows(2)
        list.adapter = rows
        root.addView(list)
        NavigationPageContent(root, R.id.nav_group).let {
            assertTrue(it.isReady())
            it.dispose()
        }
        rows.count = 0
        val cold = NavigationPageContent(root, R.id.nav_group)
        cold.dispose(); cold.dispose()
        rows.notifyDataSetChanged()
        assertFalse(cold.isReady())
    }

    @Test fun emptyLogWaitsForAnActualResultRatherThanHangingUntilTimeout() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val text = TextView(context).apply { id = R.id.textview }
        root.addView(text)
        val content = NavigationPageContent(root, R.id.nav_logcat)
        assertFalse(content.isReady())
        text.text = ""
        assertTrue(content.isReady())
        content.dispose()
    }
}
