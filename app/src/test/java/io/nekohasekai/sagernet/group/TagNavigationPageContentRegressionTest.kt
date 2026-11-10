package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.NavigationPageContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual tag-page XML/RecyclerView IDs; fake rows only control the asynchronous result boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TagNavigationPageContentRegressionTest {
    private class Rows(var count: Int = 0) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private class Fixture(count: Int = 0) : AutoCloseable {
        private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet_Blue)
        val root = LayoutInflater.from(context).inflate(R.layout.layout_tag_group, null)
        val list = root.findViewById<RecyclerView>(R.id.tag_list)
        val empty = root.findViewById<TextView>(R.id.tag_empty)
        val rows = Rows(count)
        val content: NavigationPageContent
        init {
            assertNull("Tag pages do not contain the standard group-list ID", root.findViewById<View>(R.id.group_list))
            empty.visibility = View.GONE
            list.adapter = rows
            content = NavigationPageContent(root, R.id.nav_group)
        }
        override fun close() { content.dispose() }
    }

    @Test fun nonemptyColdTagResultObservesActualTagListAdapterNotOnlyRootGroupList() {
        Fixture().use { f ->
            assertFalse(f.content.isReady())
            f.rows.count = 2
            f.rows.notifyItemRangeInserted(0, 2)
            assertEquals(View.GONE, f.empty.visibility)
            assertTrue("Tag RecyclerView result must release readiness without any empty marker", f.content.isReady())
        }
    }

    @Test fun preloadedTagsAreReadyWithoutWaitingForAnotherAdapterNotification() {
        Fixture(count = 2).use { f ->
            assertTrue(f.content.isReady())
            assertEquals(View.GONE, f.empty.visibility)
        }
    }

    @Test fun genuinelyEmptyAsyncResultReleasesGateWithoutInventingAnItem() {
        Fixture().use { f ->
            assertFalse("Initial zero rows is loading, not a completed empty result", f.content.isReady())
            f.rows.notifyDataSetChanged()
            assertEquals(0, f.rows.itemCount)
            assertTrue("The real TagAdapter.replace(emptyList()) calls notifyDataSetChanged", f.content.isReady())
        }
    }

    @Test fun emptyResultAlreadyRenderedBeforeObserverRegistrationIsAlsoReady() {
        Fixture().use { f ->
            assertFalse(f.content.isReady())
            f.empty.visibility = View.VISIBLE
            assertTrue("Explicit empty-state rendering must not hit the 2-second safety timeout", f.content.isReady())
            // Re-register after the completed empty state: there will be no new adapter event.
            val lateObserver = NavigationPageContent(f.root, R.id.nav_group)
            try { assertTrue(lateObserver.isReady()) } finally { lateObserver.dispose() }
        }
    }

    @Test fun hiddenOrInvisibleEmptyMarkerIsNotAResultAndDisposedObserverIgnoresLateLoad() {
        Fixture().use { f ->
            assertFalse(f.content.isReady())
            f.empty.visibility = View.INVISIBLE
            assertFalse(f.content.isReady())
            f.empty.visibility = View.GONE
            f.content.dispose()
            f.content.dispose()
            f.rows.count = 1
            f.rows.notifyDataSetChanged()
            assertFalse("Disposed navigation must not receive a later adapter result", f.content.isReady())
        }
    }
}
